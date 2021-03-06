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

package org.apache.spark.sql.execution.metric

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.collection.mutable

import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm._
import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.Opcodes._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql._
import org.apache.spark.sql.execution.ui.SparkPlanGraph
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils

//SQL测试指标
class SQLMetricsSuite extends SparkFunSuite with SharedSQLContext {
  import testImplicits._
  //SQL长整度量,不宜长盒
  test("LongSQLMetric should not box Long") {
    val l = SQLMetrics.createLongMetric(ctx.sparkContext, "long")
    val f = () => {
      l += 1L
      l.add(1L)
    }
    BoxingFinder.getClassReader(f.getClass).foreach { cl =>
      val boxingFinder = new BoxingFinder()
      cl.accept(boxingFinder, 0)
      assert(boxingFinder.boxingInvokes.isEmpty, s"Found boxing: ${boxingFinder.boxingInvokes}")
    }
  }

  test("Normal accumulator should do boxing") {//普通累加器装箱
    // We need this test to make sure BoxingFinder works.
    val l = ctx.sparkContext.accumulator(0L)
    val f = () => { l += 1L }
    BoxingFinder.getClassReader(f.getClass).foreach { cl =>
      val boxingFinder = new BoxingFinder()
      cl.accept(boxingFinder, 0)
      assert(boxingFinder.boxingInvokes.nonEmpty, "Found find boxing in this test")
    }
  }

  /**
   * Call `df.collect()` and verify if the collected metrics are same as "expectedMetrics".
   * 调用 `df.collect()`并验证所收集的度量是否与expectedMetrics
   * @param df `DataFrame` to run
   * @param expectedNumOfJobs number of jobs that will run 将运行的作业数
   * @param expectedMetrics the expected metrics. The format is 预期测量
   *                        `nodeId -> (operatorName, metric name -> metric value)`.
   *                        格式是` NodeID ->(operatorname,度量名称->度量值)`
   */
  private def testSparkPlanMetrics(
      df: DataFrame,
      expectedNumOfJobs: Int,
      expectedMetrics: Map[Long, (String, Map[String, Any])]): Unit = {
    val previousExecutionIds = ctx.listener.executionIdToData.keySet
    df.collect()
    ctx.sparkContext.listenerBus.waitUntilEmpty(10000)
    val executionIds = ctx.listener.executionIdToData.keySet.diff(previousExecutionIds)
    assert(executionIds.size === 1)
    val executionId = executionIds.head
    //获得Jobs列表
    val jobs = ctx.listener.getExecution(executionId).get.jobs
    // Use "<=" because there is a race condition that we may miss some jobs
    //使用“<=”，因为有竞争条件，我们可能会错过一些工作
    // TODO Change it to "=" once we fix the race condition that missing the JobStarted event.
    assert(jobs.size <= expectedNumOfJobs)
    if (jobs.size == expectedNumOfJobs) {
      // If we can track all jobs, check the metric values
      //如果我们可以跟踪所有作业,请检查度量值
      val metricValues = ctx.listener.getExecutionMetrics(executionId)
      val actualMetrics = SparkPlanGraph(df.queryExecution.executedPlan).nodes.filter { node =>
        expectedMetrics.contains(node.id)
      }.map { node =>
        val nodeMetrics = node.metrics.map { metric =>
          val metricValue = metricValues(metric.accumulatorId)
          (metric.name, metricValue)
        }.toMap
        (node.id, node.name -> nodeMetrics)
      }.toMap
      assert(expectedMetrics === actualMetrics)
    } else {
      // TODO Remove this "else" once we fix the race condition that missing the JobStarted event.
      //删除这个“else”一旦我们修正了缺少JobStarted事件的竞争条件,
      // Since we cannot track all jobs, the metric values could be wrong and we should not check
      // them.
      //由于我们无法跟踪所有作业,度量值可能是错误的,我们不应该检查它们
      //由于竞争条件,我们错过了一些作业,无法验证度量标准值
      logWarning("Due to a race condition, we miss some jobs and cannot verify the metric values")
    }
  }

  test("Project metrics") {///项目指标
    withSQLConf(
      SQLConf.UNSAFE_ENABLED.key -> "false",
      SQLConf.CODEGEN_ENABLED.key -> "false",
      SQLConf.TUNGSTEN_ENABLED.key -> "false") {
      // Assume the execution plan is 假设执行计划是
      // PhysicalRDD(nodeId = 1) -> Project(nodeId = 0)
      val df = person.select('name)
      testSparkPlanMetrics(df, 1, Map(
        0L ->("Project", Map(
          "number of rows" -> 2L)))
      )
    }
  }

  test("TungstenProject metrics") {//钨丝项目指标
    withSQLConf(
      SQLConf.UNSAFE_ENABLED.key -> "true",
      SQLConf.CODEGEN_ENABLED.key -> "true",
      SQLConf.TUNGSTEN_ENABLED.key -> "true") {
      // Assume the execution plan is
      // PhysicalRDD(nodeId = 1) -> TungstenProject(nodeId = 0)
      //假设执行计划是PhysicalRDD(nodeId = 1) - > TungstenProject(nodeId = 0)
      val df = person.select('name)
      testSparkPlanMetrics(df, 1, Map(
        0L ->("TungstenProject", Map(
          "number of rows" -> 2L)))
      )
    }
  }

  test("Filter metrics") {//过滤器的指标
    // Assume the execution plan is
    // PhysicalRDD(nodeId = 1) -> Filter(nodeId = 0)
    //假设执行计划是PhysicalRDD(nodeId = 1) - > Filter(nodeId = 0)
    val df = person.filter('age < 25)
    testSparkPlanMetrics(df, 1, Map(
      0L -> ("Filter", Map(
        "number of input rows" -> 2L,
        "number of output rows" -> 1L)))
    )
  }

  test("Aggregate metrics") {//聚合指标
    withSQLConf(
      SQLConf.UNSAFE_ENABLED.key -> "false",
      SQLConf.CODEGEN_ENABLED.key -> "false",
      SQLConf.TUNGSTEN_ENABLED.key -> "false") {
      // Assume the execution plan is
      // ... -> Aggregate(nodeId = 2) -> TungstenExchange(nodeId = 1) -> Aggregate(nodeId = 0)
      val df = testData2.groupBy().count() // 2 partitions
      testSparkPlanMetrics(df, 1, Map(
        2L -> ("Aggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 2L)),
        0L -> ("Aggregate", Map(
          "number of input rows" -> 2L,
          "number of output rows" -> 1L)))
      )

      // 2 partitions and each partition contains 2 keys
      // 2个分区,每个分区包含2个键
      val df2 = testData2.groupBy('a).count()
      testSparkPlanMetrics(df2, 1, Map(
        2L -> ("Aggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 4L)),
        0L -> ("Aggregate", Map(
          "number of input rows" -> 4L,
          "number of output rows" -> 3L)))
      )
    }
  }

  test("SortBasedAggregate metrics") {//基于排序的集合指标
    // Because SortBasedAggregate may skip different rows if the number of partitions is different,
    //因为基于排序的聚合可以跳过不同的行,如果分区的数量是不同的
    // this test should use the deterministic number of partitions.
    //这个测试应该使用确定的分区数
    withSQLConf(
      SQLConf.UNSAFE_ENABLED.key -> "false",
      SQLConf.CODEGEN_ENABLED.key -> "true",
      SQLConf.TUNGSTEN_ENABLED.key -> "true") {
      // Assume the execution plan is 假设执行计划是
      // ... -> SortBasedAggregate(nodeId = 2) -> TungstenExchange(nodeId = 1) ->
      // SortBasedAggregate(nodeId = 0)
      val df = testData2.groupBy().count() // 2 partitions
      testSparkPlanMetrics(df, 1, Map(
        2L -> ("SortBasedAggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 2L)),
        0L -> ("SortBasedAggregate", Map(
          "number of input rows" -> 2L,
          "number of output rows" -> 1L)))
      )

      // Assume the execution plan is
      // ... -> SortBasedAggregate(nodeId = 3) -> TungstenExchange(nodeId = 2)
      // -> ExternalSort(nodeId = 1)-> SortBasedAggregate(nodeId = 0)
      // 2 partitions and each partition contains 2 keys
      val df2 = testData2.groupBy('a).count()
      testSparkPlanMetrics(df2, 1, Map(
        3L -> ("SortBasedAggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 4L)),
        0L -> ("SortBasedAggregate", Map(
          "number of input rows" -> 4L,
          "number of output rows" -> 3L)))
      )
    }
  }

  test("TungstenAggregate metrics") {//钨丝聚合指标
    withSQLConf(
      SQLConf.UNSAFE_ENABLED.key -> "true",
      SQLConf.CODEGEN_ENABLED.key -> "true",
      SQLConf.TUNGSTEN_ENABLED.key -> "true") {
      // Assume the execution plan is
      // ... -> TungstenAggregate(nodeId = 2) -> Exchange(nodeId = 1)
      // -> TungstenAggregate(nodeId = 0)
      val df = testData2.groupBy().count() // 2 partitions
      testSparkPlanMetrics(df, 1, Map(
        2L -> ("TungstenAggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 2L)),
        0L -> ("TungstenAggregate", Map(
          "number of input rows" -> 2L,
          "number of output rows" -> 1L)))
      )

      // 2 partitions and each partition contains 2 keys
      //2个分区和每个分区包含2个键
      val df2 = testData2.groupBy('a).count()
      testSparkPlanMetrics(df2, 1, Map(
        2L -> ("TungstenAggregate", Map(
          "number of input rows" -> 6L,
          "number of output rows" -> 4L)),
        0L -> ("TungstenAggregate", Map(
          "number of input rows" -> 4L,
          "number of output rows" -> 3L)))
      )
    }
  }

  test("SortMergeJoin metrics") {//排序合并连接度量
    // Because SortMergeJoin may skip different rows if the number of partitions is different, this
    // test should use the deterministic number of partitions.
    //因为SortMergeJoin可能会跳过不同的行,如果分区的数量不同,这个测试应该使用确定性的分区数
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true") {
      val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
      testDataForJoin.registerTempTable("testDataForJoin")
      withTempTable("testDataForJoin") {
        // Assume the execution plan is
        // ... -> SortMergeJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
        val df = sqlContext.sql(
          "SELECT * FROM testData2 JOIN testDataForJoin ON testData2.a = testDataForJoin.a")
        testSparkPlanMetrics(df, 1, Map(
          1L -> ("SortMergeJoin", Map(
            // It's 4 because we only read 3 rows in the first partition and 1 row in the second one
            //这是4,因为我们只在第一个分区中读取3行和第二个分区中的1行
            "number of left rows" -> 4L,
            "number of right rows" -> 2L,
            "number of output rows" -> 4L)))
        )
      }
    }
  }

  test("SortMergeOuterJoin metrics") {//排序合并外部连接度量
    // Because SortMergeOuterJoin may skip different rows if the number of partitions is different,
    //如果排序合并外部连接可能跳过不同的行,如果分区的数目是不同的
    // this test should use the deterministic number of partitions.
    //这个测试应该使用确定的分区数
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true") {
      val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
      testDataForJoin.registerTempTable("testDataForJoin")
      withTempTable("testDataForJoin") {
        // Assume the execution plan is
        // ... -> SortMergeOuterJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
        val df = sqlContext.sql(
          "SELECT * FROM testData2 left JOIN testDataForJoin ON testData2.a = testDataForJoin.a")
        testSparkPlanMetrics(df, 1, Map(
          1L -> ("SortMergeOuterJoin", Map(
            // It's 4 because we only read 3 rows in the first partition and 1 row in the second one
            //这是4,因为我们只在第一个分区中读取3行和第二个分区中的1行
            "number of left rows" -> 6L,
            "number of right rows" -> 2L,
            "number of output rows" -> 8L)))
        )

        val df2 = sqlContext.sql(
          "SELECT * FROM testDataForJoin right JOIN testData2 ON testData2.a = testDataForJoin.a")
        testSparkPlanMetrics(df2, 1, Map(
          1L -> ("SortMergeOuterJoin", Map(
            // It's 4 because we only read 3 rows in the first partition and 1 row in the second one
            //这是4,因为我们只读取第一个分区中的3行和第二个中的1行
            "number of left rows" -> 2L,
            "number of right rows" -> 6L,
            "number of output rows" -> 8L)))
        )
      }
    }
  }

  test("BroadcastHashJoin metrics") {//广播的哈希连接度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key", "value")
      // Assume the execution plan is
      // ... -> BroadcastHashJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
      val df = df1.join(broadcast(df2), "key")
      testSparkPlanMetrics(df, 2, Map(
        1L -> ("BroadcastHashJoin", Map(
          "number of left rows" -> 2L,
          "number of right rows" -> 4L,
          "number of output rows" -> 2L)))
      )
    }
  }

  test("ShuffledHashJoin metrics") {//Shuffle的哈希连接度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
      testDataForJoin.registerTempTable("testDataForJoin")
      withTempTable("testDataForJoin") {
        // Assume the execution plan is
        // ... -> ShuffledHashJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
        val df = sqlContext.sql(
          "SELECT * FROM testData2 JOIN testDataForJoin ON testData2.a = testDataForJoin.a")
        testSparkPlanMetrics(df, 1, Map(
          1L -> ("ShuffledHashJoin", Map(
            "number of left rows" -> 6L,
            "number of right rows" -> 2L,
            "number of output rows" -> 4L)))
        )
      }
    }
  }

  test("ShuffledHashOuterJoin metrics") {//Shuffle哈希外连接度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "0") {
      val df1 = Seq((1, "a"), (1, "b"), (4, "c")).toDF("key", "value")
      val df2 = Seq((1, "a"), (1, "b"), (2, "c"), (3, "d")).toDF("key2", "value")
      // Assume the execution plan is
      // ... -> ShuffledHashOuterJoin(nodeId = 0)
      val df = df1.join(df2, $"key" === $"key2", "left_outer")
      testSparkPlanMetrics(df, 1, Map(
        0L -> ("ShuffledHashOuterJoin", Map(
          "number of left rows" -> 3L,
          "number of right rows" -> 4L,
          "number of output rows" -> 5L)))
      )

      val df3 = df1.join(df2, $"key" === $"key2", "right_outer")
      testSparkPlanMetrics(df3, 1, Map(
        0L -> ("ShuffledHashOuterJoin", Map(
          "number of left rows" -> 3L,
          "number of right rows" -> 4L,
          "number of output rows" -> 6L)))
      )

      val df4 = df1.join(df2, $"key" === $"key2", "outer")
      testSparkPlanMetrics(df4, 1, Map(
        0L -> ("ShuffledHashOuterJoin", Map(
          "number of left rows" -> 3L,
          "number of right rows" -> 4L,
          "number of output rows" -> 7L)))
      )
    }
  }

  test("BroadcastHashOuterJoin metrics") {//广播散列外连接度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      val df1 = Seq((1, "a"), (1, "b"), (4, "c")).toDF("key", "value")
      val df2 = Seq((1, "a"), (1, "b"), (2, "c"), (3, "d")).toDF("key2", "value")
      // Assume the execution plan is
      // ... -> BroadcastHashOuterJoin(nodeId = 0)
      val df = df1.join(broadcast(df2), $"key" === $"key2", "left_outer")
      testSparkPlanMetrics(df, 2, Map(
        0L -> ("BroadcastHashOuterJoin", Map(
          "number of left rows" -> 3L,
          "number of right rows" -> 4L,
          "number of output rows" -> 5L)))
      )

      val df3 = df1.join(broadcast(df2), $"key" === $"key2", "right_outer")
      testSparkPlanMetrics(df3, 2, Map(
        0L -> ("BroadcastHashOuterJoin", Map(
          "number of left rows" -> 3L,
          "number of right rows" -> 4L,
          "number of output rows" -> 6L)))
      )
    }
  }

  test("BroadcastNestedLoopJoin metrics") {//广播嵌套循环连接度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true") {
      val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
      testDataForJoin.registerTempTable("testDataForJoin")
      withTempTable("testDataForJoin") {
        // Assume the execution plan is
        // ... -> BroadcastNestedLoopJoin(nodeId = 1) -> TungstenProject(nodeId = 0)
        val df = sqlContext.sql(
          "SELECT * FROM testData2 left JOIN testDataForJoin ON " +
            "testData2.a * testDataForJoin.a != testData2.a + testDataForJoin.a")
        testSparkPlanMetrics(df, 3, Map(
          1L -> ("BroadcastNestedLoopJoin", Map(
              //左需要扫描两次
            "number of left rows" -> 12L, // left needs to be scanned twice
            "number of right rows" -> 2L,
            "number of output rows" -> 12L)))
        )
      }
    }
  }

  test("BroadcastLeftSemiJoinHash metrics") {//广播左半连接哈希度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key2", "value")
      // Assume the execution plan is
      // ... -> BroadcastLeftSemiJoinHash(nodeId = 0)
      val df = df1.join(broadcast(df2), $"key" === $"key2", "leftsemi")
      testSparkPlanMetrics(df, 2, Map(
        0L -> ("BroadcastLeftSemiJoinHash", Map(
          "number of left rows" -> 2L,
          "number of right rows" -> 4L,
          "number of output rows" -> 2L)))
      )
    }
  }

  test("LeftSemiJoinHash metrics") {//左半连接哈希度量
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "0") {
      val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key2", "value")
      // Assume the execution plan is
      // ... -> LeftSemiJoinHash(nodeId = 0)
      val df = df1.join(df2, $"key" === $"key2", "leftsemi")
      testSparkPlanMetrics(df, 1, Map(
        0L -> ("LeftSemiJoinHash", Map(
          "number of left rows" -> 2L,
          "number of right rows" -> 4L,
          "number of output rows" -> 2L)))
      )
    }
  }

  test("LeftSemiJoinBNL metrics") {//
    withSQLConf(SQLConf.SORTMERGE_JOIN.key -> "false") {
      val df1 = Seq((1, "1"), (2, "2")).toDF("key", "value")
      val df2 = Seq((1, "1"), (2, "2"), (3, "3"), (4, "4")).toDF("key2", "value")
      // Assume the execution plan is
      // ... -> LeftSemiJoinBNL(nodeId = 0)
      val df = df1.join(df2, $"key" < $"key2", "leftsemi")
      testSparkPlanMetrics(df, 2, Map(
        0L -> ("LeftSemiJoinBNL", Map(
          "number of left rows" -> 2L,
          "number of right rows" -> 4L,
          "number of output rows" -> 2L)))
      )
    }
  }

  test("CartesianProduct metrics") {//笛卡尔积度量
    val testDataForJoin = testData2.filter('a < 2) // TestData2(1, 1) :: TestData2(1, 2)
    testDataForJoin.registerTempTable("testDataForJoin")
    withTempTable("testDataForJoin") {
      // Assume the execution plan is
      // ... -> CartesianProduct(nodeId = 1) -> TungstenProject(nodeId = 0)
      val df = sqlContext.sql(
        "SELECT * FROM testData2 JOIN testDataForJoin")
      testSparkPlanMetrics(df, 1, Map(
        1L -> ("CartesianProduct", Map(
          "number of left rows" -> 12L, // left needs to be scanned twice 左需要扫描两次
          "number of right rows" -> 12L, // right is read 6 times 右侧阅读6次
          "number of output rows" -> 12L)))
      )
    }
  }

  test("save metrics") {//保存度量
    withTempPath { file =>
      val previousExecutionIds = ctx.listener.executionIdToData.keySet
      // Assume the execution plan is
      // PhysicalRDD(nodeId = 0)
      person.select('name).write.format("json").save(file.getAbsolutePath)
      ctx.sparkContext.listenerBus.waitUntilEmpty(10000)
      val executionIds = ctx.listener.executionIdToData.keySet.diff(previousExecutionIds)
      assert(executionIds.size === 1)
      val executionId = executionIds.head
      val jobs = ctx.listener.getExecution(executionId).get.jobs
      // Use "<=" because there is a race condition that we may miss some jobs
      // TODO Change "<=" to "=" once we fix the race condition that missing the JobStarted event.
      assert(jobs.size <= 1)
      val metricValues = ctx.listener.getExecutionMetrics(executionId)
      // Because "save" will create a new DataFrame internally, we cannot get the real metric id.
      // However, we still can check the value.
      //因为“保存”会在内部创建一个新的DataFrame，所以我们无法获取真正的度量标识。
      //但是，我们仍然可以检查值。
      assert(metricValues.values.toSeq === Seq(2L))
    }
  }

}

private case class MethodIdentifier[T](cls: Class[T], name: String, desc: String)

/**
 * If `method` is null, search all methods of this class recursively to find if they do some boxing.
 * 如果“方法”为空,则递归地搜索该类的所有方法,以查找它们是否执行了一些装箱
 * If `method` is specified, only search this method of the class to speed up the searching.
 * 如果指定了“方法”,只能搜索该类的方法以加快搜索速度
 *
 * This method will skip the methods in `visitedMethods` to avoid potential infinite cycles.
 * 该方法将跳过的方法'visitedmethods'避免潜在的无限循环
 */
private class BoxingFinder(
    method: MethodIdentifier[_] = null,
    val boxingInvokes: mutable.Set[String] = mutable.Set.empty,
    visitedMethods: mutable.Set[MethodIdentifier[_]] = mutable.Set.empty)
  extends ClassVisitor(ASM4) {

  private val primitiveBoxingClassName =
    Set("java/lang/Long",
      "java/lang/Double",
      "java/lang/Integer",
      "java/lang/Float",
      "java/lang/Short",
      "java/lang/Character",
      "java/lang/Byte",
      "java/lang/Boolean")

  override def visitMethod(
      access: Int, name: String, desc: String, sig: String, exceptions: Array[String]):
    MethodVisitor = {
    if (method != null && (method.name != name || method.desc != desc)) {
      // If method is specified, skip other methods.
      //如果指定了方法,则跳过其他方法。
      return new MethodVisitor(ASM4) {}
    }

    new MethodVisitor(ASM4) {
      override def visitMethodInsn(op: Int, owner: String, name: String, desc: String) {
        if (op == INVOKESPECIAL && name == "<init>" || op == INVOKESTATIC && name == "valueOf") {
          if (primitiveBoxingClassName.contains(owner)) {
            // Find boxing methods, e.g, new java.lang.Long(l) or java.lang.Long.valueOf(l)
            boxingInvokes.add(s"$owner.$name")
          }
        } else {
          // scalastyle:off classforname
          val classOfMethodOwner = Class.forName(owner.replace('/', '.'), false,
            //Thread.currentThread().getContextClassLoader,可以获取当前线程的引用,getContextClassLoader用来获取线程的上下文类加载器
            Thread.currentThread.getContextClassLoader)
          // scalastyle:on classforname
          val m = MethodIdentifier(classOfMethodOwner, name, desc)
          if (!visitedMethods.contains(m)) {
            // Keep track of visited methods to avoid potential infinite cycles
            //跟踪访问的方法,以避免潜在的无限周期
            visitedMethods += m
            BoxingFinder.getClassReader(classOfMethodOwner).foreach { cl =>
              visitedMethods += m
              cl.accept(new BoxingFinder(m, boxingInvokes, visitedMethods), 0)
            }
          }
        }
      }
    }
  }
}

private object BoxingFinder {

  def getClassReader(cls: Class[_]): Option[ClassReader] = {
    val className = cls.getName.replaceFirst("^.*\\.", "") + ".class"
    val resourceStream = cls.getResourceAsStream(className)
    val baos = new ByteArrayOutputStream(128)
    // Copy data over, before delegating to ClassReader -
    // else we can run out of open file handles.
    //复制数据,在委托类的读取-否则我们可以跑出来,打开的文件句柄。
    Utils.copyStream(resourceStream, baos, true)
    // ASM4 doesn't support Java 8 classes, which requires ASM5.
    // So if the class is ASM5 (E.g., java.lang.Long when using JDK8 runtime to run these codes),
    // then ClassReader will throw IllegalArgumentException,
    // However, since this is only for testing, it's safe to skip these classes.
    //然而,因为这仅用于测试,跳过这些类是安全的。
    try {
      Some(new ClassReader(new ByteArrayInputStream(baos.toByteArray)))
    } catch {
      case _: IllegalArgumentException => None
    }
  }

}
