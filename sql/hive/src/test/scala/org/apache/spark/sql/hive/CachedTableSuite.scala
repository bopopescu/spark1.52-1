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

package org.apache.spark.sql.hive

import java.io.File

import org.apache.spark.sql.columnar.{InMemoryColumnarTableScan, InMemoryRelation}
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.{SaveMode, AnalysisException, DataFrame, QueryTest}
import org.apache.spark.storage.RDDBlockId
import org.apache.spark.util.Utils

class CachedTableSuite extends QueryTest {

  def rddIdOf(tableName: String): Int = {
    val executedPlan = table(tableName).queryExecution.executedPlan
    executedPlan.collect {
      case InMemoryColumnarTableScan(_, _, relation) =>
        relation.cachedColumnBuffers.id
      case _ =>
        fail(s"Table $tableName is not cached\n" + executedPlan)
    }.head
  }

  def isMaterialized(rddId: Int): Boolean = {
    /**
      * BlockManager会运行在Driver和每个Executor上,
      * 而运行在Driver上的BlockManger负责整个Job的Block的管理工作；
      * 运行在Executor上的BlockManger负责管理该Executor上的Block,并且向Driver的BlockManager汇报Block的信息和接收来自它的命令
      */
    sparkContext.env.blockManager.get(RDDBlockId(rddId, 0)).nonEmpty
  }
  //缓存表
  test("cache table") {
    val preCacheResults = sql("SELECT * FROM src").collect().toSeq

    cacheTable("src")
    assertCached(sql("SELECT * FROM src"))

    checkAnswer(
      sql("SELECT * FROM src"),
      preCacheResults)

    assertCached(sql("SELECT * FROM src s"))

    checkAnswer(
      sql("SELECT * FROM src s"),
      preCacheResults)

    uncacheTable("src")
    assertCached(sql("SELECT * FROM src"), 0)
  }
  //缓存失效
  test("cache invalidation") {
    sql("CREATE TABLE cachedTable(key INT, value STRING)")

    sql("INSERT INTO TABLE cachedTable SELECT * FROM src")
    checkAnswer(sql("SELECT * FROM cachedTable"), table("src").collect().toSeq)

    cacheTable("cachedTable")
    checkAnswer(sql("SELECT * FROM cachedTable"), table("src").collect().toSeq)

    sql("INSERT INTO TABLE cachedTable SELECT * FROM src")
    checkAnswer(
      sql("SELECT * FROM cachedTable"),
      table("src").collect().toSeq ++ table("src").collect().toSeq)

    sql("DROP TABLE cachedTable")
  }
  //Drop缓存表
  test("Drop cached table") {
    sql("CREATE TABLE cachedTableTest(a INT)")
    cacheTable("cachedTableTest")
    sql("SELECT * FROM cachedTableTest").collect()
    sql("DROP TABLE cachedTableTest")
    intercept[AnalysisException] {
      sql("SELECT * FROM cachedTableTest").collect()
    }
  }
  //DROP不存在表
  test("DROP nonexistant table") {
    sql("DROP TABLE IF EXISTS nonexistantTable")
  }
  //对不正确的错误的缓存表uncache
  test("correct error on uncache of non-cached table") {
    intercept[IllegalArgumentException] {
      TestHive.uncacheTable("src")
    }
  }
  //缓存表'和' uncache表HiveQL语句
  test("'CACHE TABLE' and 'UNCACHE TABLE' HiveQL statement") {
    TestHive.sql("CACHE TABLE src")
    assertCached(table("src"))
    assert(TestHive.isCached("src"), "Table 'src' should be cached")

    TestHive.sql("UNCACHE TABLE src")
    assertCached(table("src"), 0)
    assert(!TestHive.isCached("src"), "Table 'src' should not be cached")
  }
  //缓存表为SELECT * FROM anothertable
  test("CACHE TABLE tableName AS SELECT * FROM anotherTable") {
    sql("CACHE TABLE testCacheTable AS SELECT * FROM src")
    assertCached(table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    uncacheTable("testCacheTable")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }
  //缓存表的选择…
  test("CACHE TABLE tableName AS SELECT ...") {
    sql("CACHE TABLE testCacheTable AS SELECT key FROM src LIMIT 10")
    assertCached(table("testCacheTable"))

    val rddId = rddIdOf("testCacheTable")
    assert(
      isMaterialized(rddId),
      "Eagerly cached in-memory table should have already been materialized")

    uncacheTable("testCacheTable")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }
//懒的表缓存
  test("CACHE LAZY TABLE tableName") {
    sql("CACHE LAZY TABLE src")
    assertCached(table("src"))

    val rddId = rddIdOf("src")
    assert(
      !isMaterialized(rddId),//懒缓存内存表不应该被热切地实现
      "Lazily cached in-memory table shouldn't be materialized eagerly")

    sql("SELECT COUNT(*) FROM src").collect()
    assert(
      isMaterialized(rddId),
      "Lazily cached in-memory table should have been materialized")

    uncacheTable("src")
    assert(!isMaterialized(rddId), "Uncached in-memory table should have been unpersisted")
  }
  //带Hive UDF的CACHE表
  test("CACHE TABLE with Hive UDF") {
    sql("CACHE TABLE udfTest AS SELECT * FROM src WHERE floor(key) = 1")
    assertCached(table("udfTest"))
    uncacheTable("udfTest")
  }
  //REFRESH TABLE还需要重新缓存数据（数据源表）
  test("REFRESH TABLE also needs to recache the data (data source tables)") {
    val tempPath: File = Utils.createTempDir()
    tempPath.delete()
    table("src").write.mode(SaveMode.Overwrite).parquet(tempPath.toString)
    sql("DROP TABLE IF EXISTS refreshTable")
    createExternalTable("refreshTable", tempPath.toString, "parquet")
    checkAnswer(
      table("refreshTable"),
      table("src").collect())
    // Cache the table. Cache the table.
    //缓存表
    sql("CACHE TABLE refreshTable")
    assertCached(table("refreshTable"))
    // Append new data. 附加新数据
    table("src").write.mode(SaveMode.Append).parquet(tempPath.toString)
    // We are still using the old data. 我们还在使用旧的数据
    assertCached(table("refreshTable"))
    checkAnswer(
      table("refreshTable"),
      table("src").collect())
    // Refresh the table.刷新表
    sql("REFRESH TABLE refreshTable")
    // We are using the new data. 我们正在使用新的数据
    assertCached(table("refreshTable"))
    checkAnswer(
      table("refreshTable"),
      table("src").unionAll(table("src")).collect())

    // Drop the table and create it again. 删除表并重新创建
    sql("DROP TABLE refreshTable")
    createExternalTable("refreshTable", tempPath.toString, "parquet")
    // It is not cached. 它没有缓存
    assert(!isCached("refreshTable"), "refreshTable should not be cached.")
    // Refresh the table. REFRESH TABLE command should not make a uncached
    // table cached. 表缓存
    sql("REFRESH TABLE refreshTable")
    checkAnswer(
      table("refreshTable"),
      table("src").unionAll(table("src")).collect())
    // It is not cached. 它没有缓存
    assert(!isCached("refreshTable"), "refreshTable should not be cached.")

    sql("DROP TABLE refreshTable")
    Utils.deleteRecursively(tempPath)
  }
  //缓存parquet表
  test("SPARK-11246 cache parquet table") {
    sql("CREATE TABLE cachedTable STORED AS PARQUET AS SELECT 1")

    cacheTable("cachedTable")
    val sparkPlan = sql("SELECT * FROM cachedTable").queryExecution.sparkPlan
    assert(sparkPlan.collect { case e: InMemoryColumnarTableScan => e }.size === 1)

    sql("DROP TABLE cachedTable")
  }
}
