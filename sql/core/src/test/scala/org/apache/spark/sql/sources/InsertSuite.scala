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

package org.apache.spark.sql.sources

import java.io.File

import org.apache.spark.sql.{SaveMode, AnalysisException, Row}
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.util.Utils

class InsertSuite extends DataSourceTest with SharedSQLContext {
  protected override lazy val sql = caseInsensitiveContext.sql _
  private lazy val sparkContext = caseInsensitiveContext.sparkContext
  private var path: File = null

  override def beforeAll(): Unit = {
    super.beforeAll()
    //创建json保存路径
    path = Utils.createTempDir()
    val rdd = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""))
    //json参数rdd: RDD[String]类型
    caseInsensitiveContext.read.json(rdd).registerTempTable("jt")
    sql(
     //USING使用org.apache.spark.sql.json.DefaultSource或者json都可以
      s"""
        |CREATE TEMPORARY TABLE jsonTable (a int, b string)
        |USING json
        |OPTIONS (
        |  path '${path.toString}'
        |)
      """.stripMargin)
  }

  override def afterAll(): Unit = {
    try {
      
      //删除临时表
      caseInsensitiveContext.dropTempTable("jsonTable")
      caseInsensitiveContext.dropTempTable("jt")
      //递归删除json数据保存路径
      Utils.deleteRecursively(path)
    } finally {
      super.afterAll()
    }
  }

  test("Simple INSERT OVERWRITE a JSONRelation") {//简单的插入覆盖一个JSON的关系
    //使用INSERT OVERWRITE TABLE插入数据
    //Overwrite模式,实际操作是,先删除数据,再写新数据
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
      """.stripMargin)

    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )
  }

  test("PreInsert casting and renaming") {//插入强类型转换和重命名
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 2, a * 4 FROM jt
      """.stripMargin)
      /**
       *+---+---+
        |  a|  b|
        +---+---+
        |  2|  4|
        |  4|  8|
        |  6| 12|
        |  8| 16|
        | 10| 20|
        | 12| 24|
        | 14| 28|
        | 16| 32|
        | 18| 36|
        | 20| 40|
        +---+---+*/
  sql("SELECT a, b FROM jsonTable").show(1)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 2, s"${i * 4}"))
    )
    //重命名字段
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 4 AS A, a * 6 as c FROM jt
      """.stripMargin)

    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 4, s"${i * 6}"))
    )
  }
  //产生相同的列,是不充许的.
  test("SELECT clause generating a different number of columns is not allowed.") {
    val message = intercept[RuntimeException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a FROM jt
      """.stripMargin)
    }.getMessage
    assert(
      message.contains("generates the same number of columns as its schema"),
      "SELECT clause generating a different number of columns should not be not allowed."
    )
  }

  test("INSERT OVERWRITE a JSONRelation multiple times") {
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    // Writing the table to less part files.
    //写到表较少的部分文件
    val rdd1 = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""), 5)
    caseInsensitiveContext.read.json(rdd1).registerTempTable("jt1")
    //使用RDD方式直接插入表数据
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt1
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    // Writing the table to more part files.
    //写到表更多的部分文件
    val rdd2 = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""), 10)
    //读取RDD[String]数据
    caseInsensitiveContext.read.json(rdd2).registerTempTable("jt2")
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt2
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a * 10, b FROM jt1
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 10, s"str$i"))
    )

    caseInsensitiveContext.dropTempTable("jt1")
    caseInsensitiveContext.dropTempTable("jt2")
  }

  test("INSERT INTO JSONRelation for now") {
    //插入覆盖表数据
    sql(
      s"""
      |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      sql("SELECT a, b FROM jt").collect()
    )
   //插入表数据,注意没有OVERWRITE
   //Overwrite模式,实际操作是,先删除数据,再写新数据

    sql(
      s"""
         |INSERT INTO TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      sql("SELECT a, b FROM jt UNION ALL SELECT a, b FROM jt").collect()
    )
  }
  //在查询时,不允许在表上写入
  test("it is not allowed to write to a table while querying it.") {
    val message = intercept[AnalysisException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jsonTable
      """.stripMargin)
    }.getMessage
    assert(
      message.contains("Cannot insert overwrite into table that is also being read from."),
      "INSERT OVERWRITE to a table while querying it should not be allowed.")
  }

  test("Caching")  {//缓存
    // write something to the jsonTable
    //写点东西给jsontable
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
      """.stripMargin)
    // Cached Query Execution 缓存查询执行
    caseInsensitiveContext.cacheTable("jsonTable")
    //判断是否存在缓存表数据
    assertCached(sql("SELECT * FROM jsonTable"))
    checkAnswer(
      sql("SELECT * FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i")))

    assertCached(sql("SELECT a FROM jsonTable"))
    checkAnswer(
      sql("SELECT a FROM jsonTable"),
      (1 to 10).map(Row(_)).toSeq)

    assertCached(sql("SELECT a FROM jsonTable WHERE a < 5"))
    checkAnswer(
      sql("SELECT a FROM jsonTable WHERE a < 5"),
      (1 to 4).map(Row(_)).toSeq)

    assertCached(sql("SELECT a * 2 FROM jsonTable"))
    checkAnswer(
      sql("SELECT a * 2 FROM jsonTable"),
      (1 to 10).map(i => Row(i * 2)).toSeq)

    assertCached(sql(
      "SELECT x.a, y.a FROM jsonTable x JOIN jsonTable y ON x.a = y.a + 1"), 2)
    checkAnswer(sql(
      "SELECT x.a, y.a FROM jsonTable x JOIN jsonTable y ON x.a = y.a + 1"),
      (2 to 10).map(i => Row(i, i - 1)).toSeq)

    // Insert overwrite and keep the same schema.
      //插入覆盖并保持相同的模式
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 2, b FROM jt
      """.stripMargin)
    // jsonTable should be recached.
    assertCached(sql("SELECT * FROM jsonTable"))
    // TODO we need to invalidate the cached data in InsertIntoHadoopFsRelation
//    // The cached data is the new data.
//    checkAnswer(
//      sql("SELECT a, b FROM jsonTable"),
//      sql("SELECT a * 2, b FROM jt").collect())
//
//    // Verify uncaching
//    caseInsensitiveContext.uncacheTable("jsonTable")
//    assertCached(sql("SELECT * FROM jsonTable"), 0)
  }
  //它不允许插入的关系,不是一个可插入的关系
  test("it's not allowed to insert into a relation that is not an InsertableRelation") {
    sql(
      """
        |CREATE TEMPORARY TABLE oneToTen
        |USING org.apache.spark.sql.sources.SimpleScanSource
        |OPTIONS (
        |  From '1',
        |  To '10'
        |)
      """.stripMargin)

    checkAnswer(
      sql("SELECT * FROM oneToTen"),
      (1 to 10).map(Row(_)).toSeq
    )

    val message = intercept[AnalysisException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE oneToTen SELECT CAST(a AS INT) FROM jt
        """.stripMargin)
    }.getMessage
    assert(
      message.contains("does not allow insertion."),
      "It is not allowed to insert into a table that is not an InsertableRelation."
    )

    caseInsensitiveContext.dropTempTable("oneToTen")
  }
}
