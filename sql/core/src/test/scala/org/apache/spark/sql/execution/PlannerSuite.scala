/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.SparkFunSuite
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{execution, Row, SQLConf}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Ascending, Attribute, Literal, SortOrder}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.execution.joins.{BroadcastHashJoin, ShuffledHashJoin}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._

//规划套件
class PlannerSuite extends SparkFunSuite with SharedSQLContext {
  import testImplicits._

  setupTestData()
  //测试部分聚合计划
  private def testPartialAggregationPlan(query: LogicalPlan): Unit = {
    val _ctx = ctx
    import _ctx.planner._
    val plannedOption = HashAggregation(query).headOption.orElse(Aggregation(query).headOption)
    val planned =
      plannedOption.getOrElse(
        fail(s"Could query play aggregation query $query. Is it an aggregation query?"))
    //使用case 
    val aggregations = planned.collect { case n if n.nodeName contains "Aggregate" => n }

    // For the new aggregation code path, there will be three aggregate operator for
    // distinct aggregations.
    //对于新的聚合代码路径,将有不同的聚合操作
    assert(
      aggregations.size == 2 || aggregations.size == 3,
      s"The plan of query $query does not have partial aggregations.")
  }

  test("unions are collapsed") {//联合折叠
    val _ctx = ctx
    import _ctx.planner._
    val query = testData.unionAll(testData).unionAll(testData).logicalPlan
    val planned = BasicOperators(query).head
    val logicalUnions = query collect { case u: logical.Union => u }
    val physicalUnions = planned collect { case u: execution.Union => u }

    assert(logicalUnions.size === 2)
    assert(physicalUnions.size === 1)
  }

  test("count is partially aggregated") {//计数是部分聚合
    val query = testData.groupBy('value).agg(count('key)).queryExecution.analyzed
    testPartialAggregationPlan(query)
  }

  test("count distinct is partially aggregated") {//计数不同的是部分聚合
    val query = testData.groupBy('value).agg(countDistinct('key)).queryExecution.analyzed
    testPartialAggregationPlan(query)
  }

  test("mixed aggregates are partially aggregated") {//混合聚合部分聚合
    val query =
      testData.groupBy('value).agg(count('value), countDistinct('key)).queryExecution.analyzed
    testPartialAggregationPlan(query)
  }
  //在字节估计连接广播哈希限制优化
  test("sizeInBytes estimation of limit operator for broadcast hash join optimization") {
    def checkPlan(fieldTypes: Seq[DataType], newThreshold: Int): Unit = {
      ctx.setConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD, newThreshold)
      val fields = fieldTypes.zipWithIndex.map {
        case (dataType, index) => StructField(s"c${index}", dataType, true)
      } :+ StructField("key", IntegerType, true)
      val schema = StructType(fields)//StructType代表一张表,StructField代表一个字段
      val row = Row.fromSeq(Seq.fill(fields.size)(null))
      val rowRDD = ctx.sparkContext.parallelize(row :: Nil)
      ctx.createDataFrame(rowRDD, schema).registerTempTable("testLimit")

      val planned = sql(
        """
          |SELECT l.a, l.b
          |FROM testData2 l JOIN (SELECT * FROM testLimit LIMIT 1) r ON (l.a = r.key)
        """.stripMargin).queryExecution.executedPlan

      val broadcastHashJoins = planned.collect { case join: BroadcastHashJoin => join }
      val shuffledHashJoins = planned.collect { case join: ShuffledHashJoin => join }

      assert(broadcastHashJoins.size === 1, "Should use broadcast hash join")
      assert(shuffledHashJoins.isEmpty, "Should not use shuffled hash join")

      ctx.dropTempTable("testLimit")
    }

    val origThreshold = ctx.conf.autoBroadcastJoinThreshold

    val simpleTypes =
      NullType ::
      BooleanType ::
      ByteType ::
      ShortType ::
      IntegerType ::
      LongType ::
      FloatType ::
      DoubleType ::
      DecimalType(10, 5) ::
      DecimalType.SYSTEM_DEFAULT ::
      DateType ::
      TimestampType ::
      StringType ::
      BinaryType :: Nil

    checkPlan(simpleTypes, newThreshold = 16434)

    val complexTypes =
      ArrayType(DoubleType, true) ::
      ArrayType(StringType, false) ::
      MapType(IntegerType, StringType, true) ::
      MapType(IntegerType, ArrayType(DoubleType), false) ::
      StructType(Seq(
        StructField("a", IntegerType, nullable = true),
        StructField("b", ArrayType(DoubleType), nullable = false),
        StructField("c", DoubleType, nullable = false))) :: Nil

    checkPlan(complexTypes, newThreshold = 901617)

    ctx.setConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD, origThreshold)
  }
  //内存统计传播关系
  test("InMemoryRelation statistics propagation") {
    val origThreshold = ctx.conf.autoBroadcastJoinThreshold
    ctx.setConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD, 81920)

    testData.limit(3).registerTempTable("tiny")
    sql("CACHE TABLE tiny")

    val a = testData.as("a")
    val b = ctx.table("tiny").as("b")
    val planned = a.join(b, $"a.key" === $"b.key").queryExecution.executedPlan

    val broadcastHashJoins = planned.collect { case join: BroadcastHashJoin => join }
    val shuffledHashJoins = planned.collect { case join: ShuffledHashJoin => join }

    assert(broadcastHashJoins.size === 1, "Should use broadcast hash join")
    assert(shuffledHashJoins.isEmpty, "Should not use shuffled hash join")

    ctx.setConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD, origThreshold)
  }
  //有效的限制>项目>排序
  test("efficient limit -> project -> sort") {
    {
      val query =
        testData.select('key, 'value).sort('key).limit(2).logicalPlan
      val planned = ctx.planner.TakeOrderedAndProject(query)
      assert(planned.head.isInstanceOf[execution.TakeOrderedAndProject])
      assert(planned.head.output === testData.select('key, 'value).logicalPlan.output)
    }

    {
      // We need to make sure TakeOrderedAndProject's output is correct when we push a project
      // into it.
      val query =
        testData.select('key, 'value).sort('key).select('value, 'key).limit(2).logicalPlan
      val planned = ctx.planner.TakeOrderedAndProject(query)
      assert(planned.head.isInstanceOf[execution.TakeOrderedAndProject])
      assert(planned.head.output === testData.select('value, 'key).logicalPlan.output)
    }
  }
  //分区集合
  test("PartitioningCollection") {
    withTempTable("normal", "small", "tiny") {
      testData.registerTempTable("normal")
      testData.limit(10).registerTempTable("small")
      testData.limit(3).registerTempTable("tiny")

      // Disable broadcast join
      //禁用广播连接
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
        {
          val numExchanges = sql(
            """
              |SELECT *
              |FROM
              |  normal JOIN small ON (normal.key = small.key)
              |  JOIN tiny ON (small.key = tiny.key)
            """.stripMargin
          ).queryExecution.executedPlan.collect {
            case exchange: Exchange => exchange
          }.length
          assert(numExchanges === 3)
        }

        {
          // This second query joins on different keys:
          //这个第二个查询连接在不同的键上：
          val numExchanges = sql(
            """
              |SELECT *
              |FROM
              |  normal JOIN small ON (normal.key = small.key)
              |  JOIN tiny ON (normal.key = tiny.key)
            """.stripMargin
          ).queryExecution.executedPlan.collect {
            case exchange: Exchange => exchange
          }.length
          assert(numExchanges === 3)
        }

      }
    }
  }

  // --- Unit tests of EnsureRequirements ---------------------------------------------------------

  // When it comes to testing whether EnsureRequirements properly ensures distribution requirements,
  //当涉及到测试是否确保要求适当确保分配要求
  // there two dimensions that need to be considered: are the child partitionings compatible and
  // do they satisfy the distribution requirements? As a result, we need at least four test cases.
  //有两个方面需要考虑:是子分割兼容是否满足分配要求?因此,我们至少需要四个测试用例。

  private def assertDistributionRequirementsAreSatisfied(outputPlan: SparkPlan): Unit = {
    if (outputPlan.children.length > 1
        && outputPlan.requiredChildDistribution.toSet != Set(UnspecifiedDistribution)) {
      val childPartitionings = outputPlan.children.map(_.outputPartitioning)
      if (!Partitioning.allCompatible(childPartitionings)) {
        fail(s"Partitionings are not compatible: $childPartitionings")
      }
    }
    outputPlan.children.zip(outputPlan.requiredChildDistribution).foreach {
      case (child, requiredDist) =>
        assert(child.outputPartitioning.satisfies(requiredDist),
          s"$child output partitioning does not satisfy $requiredDist:\n$outputPlan")
    }
  }
  //确保具有满足分发的不兼容子分区的要求
  test("EnsureRequirements with incompatible child partitionings which satisfy distribution") {
    // Consider an operator that requires inputs that are clustered by two expressions (e.g.
    // sort merge join where there are multiple columns in the equi-join condition)
    //考虑一个需要由两个表达式聚类的输入的运算符（例如，排序合并连接，等于连接条件中有多个列）
    val clusteringA = Literal(1) :: Nil
    val clusteringB = Literal(2) :: Nil
    val distribution = ClusteredDistribution(clusteringA ++ clusteringB)
    // Say that the left and right inputs are each partitioned by _one_ of the two join columns:
    //假设左和右输入都由两个连接列的_one_分隔：
    val leftPartitioning = HashPartitioning(clusteringA, 1)
    val rightPartitioning = HashPartitioning(clusteringB, 1)
    // Individually, each input's partitioning satisfies the clustering distribution:
    //单独地，每个输入的分区满足聚类分布：
    assert(leftPartitioning.satisfies(distribution))
    assert(rightPartitioning.satisfies(distribution))
    // However, these partitionings are not compatible with each other, so we still need to
    // repartition both inputs prior to performing the join:
    //但是，这些分区是不兼容的，所以我们仍然需要,在执行连接之前重新分配两个输入：
    assert(!leftPartitioning.compatibleWith(rightPartitioning))
    assert(!rightPartitioning.compatibleWith(leftPartitioning))
    val inputPlan = DummySparkPlan(
      children = Seq(
        DummySparkPlan(outputPartitioning = leftPartitioning),
        DummySparkPlan(outputPartitioning = rightPartitioning)
      ),
      requiredChildDistribution = Seq(distribution, distribution),
      requiredChildOrdering = Seq(Seq.empty, Seq.empty)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case Exchange(_, _) => true }.isEmpty) {
      fail(s"Exchange should have been added:\n$outputPlan")
    }
  }
  //确保具有不同数量输出分区的子分区的要求
  test("EnsureRequirements with child partitionings with different numbers of output partitions") {
    // This is similar to the previous test, except it checks that partitionings are not compatible
    // unless they produce the same number of partitions.
    //这与以前的测试类似，只不过它检查分区是不兼容的除非它们产生相同数量的分区。
    val clustering = Literal(1) :: Nil
    val distribution = ClusteredDistribution(clustering)
    val inputPlan = DummySparkPlan(
      children = Seq(
        DummySparkPlan(outputPartitioning = HashPartitioning(clustering, 1)),
        DummySparkPlan(outputPartitioning = HashPartitioning(clustering, 2))
      ),
      requiredChildDistribution = Seq(distribution, distribution),
      requiredChildOrdering = Seq(Seq.empty, Seq.empty)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
  }
  //确保具有不满足分发的兼容子分区的要求
  test("EnsureRequirements with compatible child partitionings that do not satisfy distribution") {
    val distribution = ClusteredDistribution(Literal(1) :: Nil)
    // The left and right inputs have compatible partitionings but they do not satisfy the
    // distribution because they are clustered on different columns. Thus, we need to shuffle.
    //左和右输入具有兼容的分区,但不符合分布，因为它们聚集在不同的列上。 因此，我们需要洗牌。
    val childPartitioning = HashPartitioning(Literal(2) :: Nil, 1)
    assert(!childPartitioning.satisfies(distribution))
    val inputPlan = DummySparkPlan(
      children = Seq(
        DummySparkPlan(outputPartitioning = childPartitioning),
        DummySparkPlan(outputPartitioning = childPartitioning)
      ),
      requiredChildDistribution = Seq(distribution, distribution),
      requiredChildOrdering = Seq(Seq.empty, Seq.empty)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case Exchange(_, _) => true }.isEmpty) {
      fail(s"Exchange should have been added:\n$outputPlan")
    }
  }
  //确保具有满足分发的兼容子分区的要求
  test("EnsureRequirements with compatible child partitionings that satisfy distribution") {
    // In this case, all requirements are satisfied and no exchange should be added.
    //在这种情况下,所有要求都得到满足,不得添加任何交换,
    val distribution = ClusteredDistribution(Literal(1) :: Nil)
    val childPartitioning = HashPartitioning(Literal(1) :: Nil, 5)
    assert(childPartitioning.satisfies(distribution))
    val inputPlan = DummySparkPlan(
      children = Seq(
        DummySparkPlan(outputPartitioning = childPartitioning),
        DummySparkPlan(outputPartitioning = childPartitioning)
      ),
      requiredChildDistribution = Seq(distribution, distribution),
      requiredChildOrdering = Seq(Seq.empty, Seq.empty)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case Exchange(_, _) => true }.nonEmpty) {
      fail(s"Exchange should not have been added:\n$outputPlan")
    }
  }

  // This is a regression test for SPARK-9703
  //这是SPARK-9703的回归测试如果只有订购要求不满足要求，则不要重新分配
  test("EnsureRequirements should not repartition if only ordering requirement is unsatisfied") {
    // Consider an operator that imposes both output distribution and  ordering requirements on its
    // children, such as sort sort merge join. If the distribution requirements are satisfied but
    // the output ordering requirements are unsatisfied, then the planner should only add sorts and
    // should not need to add additional shuffles / exchanges.
    val outputOrdering = Seq(SortOrder(Literal(1), Ascending))
    val distribution = ClusteredDistribution(Literal(1) :: Nil)
    val inputPlan = DummySparkPlan(
      children = Seq(
        DummySparkPlan(outputPartitioning = SinglePartition),
        DummySparkPlan(outputPartitioning = SinglePartition)
      ),
      requiredChildDistribution = Seq(distribution, distribution),
      requiredChildOrdering = Seq(outputOrdering, outputOrdering)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case Exchange(_, _) => true }.nonEmpty) {
      fail(s"No Exchanges should have been added:\n$outputPlan")
    }
  }
  //当没有现有的订单时，EnsureRequirements会添加排序
  test("EnsureRequirements adds sort when there is no existing ordering") {
    val orderingA = SortOrder(Literal(1), Ascending)
    val orderingB = SortOrder(Literal(2), Ascending)
    assert(orderingA != orderingB)
    val inputPlan = DummySparkPlan(
      children = DummySparkPlan(outputOrdering = Seq.empty) :: Nil,
      requiredChildOrdering = Seq(Seq(orderingB)),
      requiredChildDistribution = Seq(UnspecifiedDistribution)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case s: TungstenSort => true; case s: Sort => true }.isEmpty) {
      fail(s"Sort should have been added:\n$outputPlan")
    }
  }
  //当需要排序是现有排序的前缀时，请务必跳过排序
  test("EnsureRequirements skips sort when required ordering is prefix of existing ordering") {
    val orderingA = SortOrder(Literal(1), Ascending)
    val orderingB = SortOrder(Literal(2), Ascending)
    assert(orderingA != orderingB)
    val inputPlan = DummySparkPlan(
      children = DummySparkPlan(outputOrdering = Seq(orderingA, orderingB)) :: Nil,
      requiredChildOrdering = Seq(Seq(orderingA)),
      requiredChildDistribution = Seq(UnspecifiedDistribution)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case s: TungstenSort => true; case s: Sort => true }.nonEmpty) {
      fail(s"No sorts should have been added:\n$outputPlan")
    }
  }

  // This is a regression test for SPARK-11135
  //EnsureRequirements在需要时添加排序，而不是现有排序的前缀
  test("EnsureRequirements adds sort when required ordering isn't a prefix of existing ordering") {
    val orderingA = SortOrder(Literal(1), Ascending)
    val orderingB = SortOrder(Literal(2), Ascending)
    assert(orderingA != orderingB)
    val inputPlan = DummySparkPlan(
      children = DummySparkPlan(outputOrdering = Seq(orderingA)) :: Nil,
      requiredChildOrdering = Seq(Seq(orderingA, orderingB)),
      requiredChildDistribution = Seq(UnspecifiedDistribution)
    )
    val outputPlan = EnsureRequirements(sqlContext).apply(inputPlan)
    assertDistributionRequirementsAreSatisfied(outputPlan)
    if (outputPlan.collect { case s: TungstenSort => true; case s: Sort => true }.isEmpty) {
      fail(s"Sort should have been added:\n$outputPlan")
    }
  }

  // ---------------------------------------------------------------------------------------------
}

// Used for unit-testing EnsureRequirements
private case class DummySparkPlan(
    override val children: Seq[SparkPlan] = Nil,
    override val outputOrdering: Seq[SortOrder] = Nil,
    override val outputPartitioning: Partitioning = UnknownPartitioning(0),
    override val requiredChildDistribution: Seq[Distribution] = Nil,
    override val requiredChildOrdering: Seq[Seq[SortOrder]] = Nil
  ) extends SparkPlan {
  override protected def doExecute(): RDD[InternalRow] = throw new NotImplementedError
  override def output: Seq[Attribute] = Seq.empty
}
