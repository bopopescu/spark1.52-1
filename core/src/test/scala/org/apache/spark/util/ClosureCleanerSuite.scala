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

package org.apache.spark.util

import java.io.NotSerializableException
import java.util.Random

import org.apache.spark.LocalSparkContext._
import org.apache.spark.{SparkContext, SparkException, SparkFunSuite, TaskContext}
import org.apache.spark.partial.CountEvaluator
import org.apache.spark.rdd.RDD
/**
 * 
 */
class ClosureCleanerSuite extends SparkFunSuite {
  test("closures  inside an object") {//对象中的闭包
    assert(TestObject.run() === 30) // 6 + 7 + 8 + 9
  }

  test("closures  inside a class") {//类中的闭包
    val obj = new TestClass
    assert(obj.run() === 30) // 6 + 7 + 8 + 9
  }

  test("closures inside a class with no default constructor") {//一个没有默认构造函数的类中的闭包
    val obj = new TestClassWithoutDefaultConstructor(5)
    assert(obj.run() === 30) // 6 + 7 + 8 + 9
  }

  test("closures  that don't use fields of the outer class") {//不使用外部类闭包的字段
    val obj = new TestClassWithoutFieldAccess
    assert(obj.run() === 30) // 6 + 7 + 8 + 9
  }

  test("nested closures inside an object") {//对象内的嵌套闭包
    assert(TestObjectWithNesting.run() === 96) // 4 * (1+2+3+4) + 4 * (1+2+3+4) + 16 * 1
  }

  test("nested closures inside a class") {//一个类中嵌套闭包
    val obj = new TestClassWithNesting(1)
    assert(obj.run() === 96) // 4 * (1+2+3+4) + 4 * (1+2+3+4) + 16 * 1
  }
   //顶层返回语句关闭时确定清理时间
  test("toplevel return statements in closures are identified at cleaning time") {
    intercept[ReturnStatementInClosureException] {
      TestObjectWithBogusReturns.run()
    }
  }
  //来自命名函数的返回语句嵌套在闭包中不会引发异常
  test("return statements from named functions nested in closures don't raise exceptions") {
    val result = TestObjectWithNestedReturns.run()
    assert(result === 1)
  }

  test("user provided closures are actually cleaned") {//用户提供的闭包实际上是清理

    // We use return statements as an indication that a closure is actually being cleaned
    // We expect closure cleaner to find the return statements in the user provided closures
    def expectCorrectException(body: => Unit): Unit = {
      try {
        body
      } catch {
        case rse: ReturnStatementInClosureException => // Success!
        case e @ (_: NotSerializableException | _: SparkException) =>
          fail(s"Expected ReturnStatementInClosureException, but got $e.\n" +
            "This means the closure provided by user is not actually cleaned.")
      }
    }

    withSpark(new SparkContext("local", "test")) { sc =>
      val rdd = sc.parallelize(1 to 10)
      val pairRdd = rdd.map { i => (i, i) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMap(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFlatMap(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFilter(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testSortBy(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testGroupBy(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testKeyBy(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMapPartitions(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMapPartitionsWithIndex(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMapPartitionsWithContext(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFlatMapWith(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFilterWith(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testForEachWith(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMapWith(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testZipPartitions2(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testZipPartitions3(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testZipPartitions4(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testForeach(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testForeachPartition(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testReduce(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testTreeReduce(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFold(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testAggregate(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testTreeAggregate(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testCombineByKey(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testAggregateByKey(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFoldByKey(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testReduceByKey(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testReduceByKeyLocally(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testMapValues(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testFlatMapValues(pairRdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testForeachAsync(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testForeachPartitionAsync(rdd) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testRunJob1(sc) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testRunJob2(sc) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testRunApproximateJob(sc) }
      expectCorrectException { TestUserClosuresActuallyCleaned.testSubmitJob(sc) }
    }
  }

  test("createNullValue") {
    new TestCreateNullValue().run()
  }
}

// A non-serializable class we create in closures to make sure that we aren't
//一个不可序列化的类,我们创建在关闭,以确保保持不必要的变量引用
// keeping references to unneeded variables from our outer closures.
class NonSerializable(val id: Int = -1) {
  override def equals(other: Any): Boolean = {
    other match {
      case o: NonSerializable => id == o.id
      case _ => false
    }
  }
}

object TestObject {
  def run(): Int = {
    var nonSer = new NonSerializable
    val x = 5
    //柯里化(Currying)指的是将原来接受两个参数的函数变成新的接受一个参数的函数的过程,
    //新的函数返回一个以原有第二个参数为参数的函数.
    
    withSpark(new SparkContext("local", "test")) { sc =>
      //匿名方法
      //柯里化第二个匿名方法调用方式
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      //6+7+8+9=30
      nums.map(_ + x).reduce(_ + _) //返回值 Int = 30
    }
  }
}

class TestClass extends Serializable {
  var x = 5

  def getX: Int = x

  def run(): Int = {
    var nonSer = new NonSerializable
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      nums.map(_ + getX).reduce(_ + _)
    }
  }
}

class TestClassWithoutDefaultConstructor(x: Int) extends Serializable {
  def getX: Int = x

  def run(): Int = {
    var nonSer = new NonSerializable
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      nums.map(_ + getX).reduce(_ + _)
    }
  }
}

// This class is not serializable, but we aren't using any of its fields in our
// closures, so they won't have a $outer pointing to it and should still work.
//这个类不是可序列化的，但是我们没有使用任何它的字段
//关闭，所以他们不会有一个$外指向它，应该仍然工作。
class TestClassWithoutFieldAccess {
  var nonSer = new NonSerializable

  def run(): Int = {
    var nonSer2 = new NonSerializable
    var x = 5
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      nums.map(_ + x).reduce(_ + _)
    }
  }
}

object TestObjectWithBogusReturns {
  def run(): Int = {
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      // this return is invalid since it will transfer control outside the closure
      //此返回是无效的,因为它将在闭包的外部传输控制
      nums.map {x => return 1 ; x * 2}
      1
    }
  }
}

object TestObjectWithNestedReturns {
  def run(): Int = {
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      nums.map {x =>
        // this return is fine since it will not transfer control outside the closure
        //这个回报是很好的，因为它不会将控制权转移到关闭之外
        def foo(): Int = { return 5; 1 }
        foo()
      }
      1
    }
  }
}

object TestObjectWithNesting {
  def run(): Int = {
    var nonSer = new NonSerializable
    var answer = 0
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      var y = 1
      for (i <- 1 to 4) {
        var nonSer2 = new NonSerializable
        var x = i
        answer += nums.map(_ + x + y).reduce(_ + _)
      }
      answer
    }
  }
}

class TestClassWithNesting(val y: Int) extends Serializable {
  def getY: Int = y

  def run(): Int = {
    var nonSer = new NonSerializable
    var answer = 0
    withSpark(new SparkContext("local", "test")) { sc =>
      val nums = sc.parallelize(Array(1, 2, 3, 4))
      for (i <- 1 to 4) {
        var nonSer2 = new NonSerializable
        var x = i
        answer += nums.map(_ + x + getY).reduce(_ + _)
      }
      answer
    }
  }
}

/**
 * Test whether closures passed in through public APIs are actually cleaned.
 * 测试通过公共API传递的闭包是否被实际清理
  *
 * We put a return statement in each of these closures as a mechanism to detect whether the
 * ClosureCleaner actually cleaned our closure. If it did, then it would throw an appropriate
 * exception explicitly complaining about the return statement. Otherwise, we know the
 * ClosureCleaner did not actually clean our closure, in which case we should fail the test.
 */
private object TestUserClosuresActuallyCleaned {
  def testMap(rdd: RDD[Int]): Unit = { rdd.map { _ => return; 0 }.count() }
  def testFlatMap(rdd: RDD[Int]): Unit = { rdd.flatMap { _ => return; Seq() }.count() }
  def testFilter(rdd: RDD[Int]): Unit = { rdd.filter { _ => return; true }.count() }
  def testSortBy(rdd: RDD[Int]): Unit = { rdd.sortBy { _ => return; 1 }.count() }
  def testKeyBy(rdd: RDD[Int]): Unit = { rdd.keyBy { _ => return; 1 }.count() }
  def testGroupBy(rdd: RDD[Int]): Unit = { rdd.groupBy { _ => return; 1 }.count() }
  def testMapPartitions(rdd: RDD[Int]): Unit = { rdd.mapPartitions { it => return; it }.count() }
  def testMapPartitionsWithIndex(rdd: RDD[Int]): Unit = {
    rdd.mapPartitionsWithIndex { (_, it) => return; it }.count()
  }
  def testFlatMapWith(rdd: RDD[Int]): Unit = {
    rdd.flatMapWith ((index: Int) => new Random(index + 42)){ (_, it) => return; Seq() }.count()
  }
  def testMapWith(rdd: RDD[Int]): Unit = {
    rdd.mapWith ((index: Int) => new Random(index + 42)){ (_, it) => return; 0 }.count()
  }
  def testFilterWith(rdd: RDD[Int]): Unit = {
    rdd.filterWith ((index: Int) => new Random(index + 42)){ (_, it) => return; true }.count()
  }
  def testForEachWith(rdd: RDD[Int]): Unit = {
    rdd.foreachWith ((index: Int) => new Random(index + 42)){ (_, it) => return }
  }
  def testMapPartitionsWithContext(rdd: RDD[Int]): Unit = {
    rdd.mapPartitionsWithContext { (_, it) => return; it }.count()
  }
  def testZipPartitions2(rdd: RDD[Int]): Unit = {
    rdd.zipPartitions(rdd) { case (it1, it2) => return; it1 }.count()
  }
  def testZipPartitions3(rdd: RDD[Int]): Unit = {
    rdd.zipPartitions(rdd, rdd) { case (it1, it2, it3) => return; it1 }.count()
  }
  def testZipPartitions4(rdd: RDD[Int]): Unit = {
    rdd.zipPartitions(rdd, rdd, rdd) { case (it1, it2, it3, it4) => return; it1 }.count()
  }
  def testForeach(rdd: RDD[Int]): Unit = { rdd.foreach { _ => return } }
  def testForeachPartition(rdd: RDD[Int]): Unit = { rdd.foreachPartition { _ => return } }
  def testReduce(rdd: RDD[Int]): Unit = { rdd.reduce { case (_, _) => return; 1 } }
  def testTreeReduce(rdd: RDD[Int]): Unit = { rdd.treeReduce { case (_, _) => return; 1 } }
  def testFold(rdd: RDD[Int]): Unit = { rdd.fold(0) { case (_, _) => return; 1 } }
  def testAggregate(rdd: RDD[Int]): Unit = {
    rdd.aggregate(0)({ case (_, _) => return; 1 }, { case (_, _) => return; 1 })
  }
  def testTreeAggregate(rdd: RDD[Int]): Unit = {
    rdd.treeAggregate(0)({ case (_, _) => return; 1 }, { case (_, _) => return; 1 })
  }

  // Test pair RDD functions
  //测试RDD对功能
  def testCombineByKey(rdd: RDD[(Int, Int)]): Unit = {
    rdd.combineByKey(
      { _ => return; 1 }: Int => Int,
      { case (_, _) => return; 1 }: (Int, Int) => Int,
      { case (_, _) => return; 1 }: (Int, Int) => Int
    ).count()
  }
  def testAggregateByKey(rdd: RDD[(Int, Int)]): Unit = {
    rdd.aggregateByKey(0)({ case (_, _) => return; 1 }, { case (_, _) => return; 1 }).count()
  }
  def testFoldByKey(rdd: RDD[(Int, Int)]): Unit = { rdd.foldByKey(0) { case (_, _) => return; 1 } }
  def testReduceByKey(rdd: RDD[(Int, Int)]): Unit = { rdd.reduceByKey { case (_, _) => return; 1 } }
  def testReduceByKeyLocally(rdd: RDD[(Int, Int)]): Unit = {
    rdd.reduceByKeyLocally { case (_, _) => return; 1 }
  }
  def testMapValues(rdd: RDD[(Int, Int)]): Unit = { rdd.mapValues { _ => return; 1 } }
  def testFlatMapValues(rdd: RDD[(Int, Int)]): Unit = { rdd.flatMapValues { _ => return; Seq() } }

  // Test async RDD actions 测试异步RDD操作
  def testForeachAsync(rdd: RDD[Int]): Unit = { rdd.foreachAsync { _ => return } }
  def testForeachPartitionAsync(rdd: RDD[Int]): Unit = { rdd.foreachPartitionAsync { _ => return } }

  // Test SparkContext runJob
  def testRunJob1(sc: SparkContext): Unit = {
    val rdd = sc.parallelize(1 to 10, 10)
    sc.runJob(rdd, { (ctx: TaskContext, iter: Iterator[Int]) => return; 1 } )
  }
  def testRunJob2(sc: SparkContext): Unit = {
    val rdd = sc.parallelize(1 to 10, 10)
    sc.runJob(rdd, { iter: Iterator[Int] => return; 1 } )
  }
  def testRunApproximateJob(sc: SparkContext): Unit = {
    val rdd = sc.parallelize(1 to 10, 10)
    val evaluator = new CountEvaluator(1, 0.5)
    sc.runApproximateJob(
      rdd, { (ctx: TaskContext, iter: Iterator[Int]) => return; 1L }, evaluator, 1000)
  }
  def testSubmitJob(sc: SparkContext): Unit = {
    val rdd = sc.parallelize(1 to 10, 10)
    sc.submitJob(
      rdd,
      { _ => return; 1 }: Iterator[Int] => Int,
      Seq.empty,
      { case (_, _) => return }: (Int, Int) => Unit,
      { return }
    )
  }
}

class TestCreateNullValue {

  var x = 5

  def getX: Int = x

  def run(): Unit = {
    val bo: Boolean = true
    val c: Char = '1'
    val b: Byte = 1
    val s: Short = 1
    val i: Int = 1
    val l: Long = 1
    val f: Float = 1
    val d: Double = 1

    // Bring in all primitive types into the closure such that they become
    // parameters of the closure constructor. This allows us to test whether
    // null values are created correctly for each type.
    val nestedClosure = () => {
      // scalastyle:off println
      if (s.toString == "123") { // Don't really output them to avoid noisy
        println(bo)
        println(c)
        println(b)
        println(s)
        println(i)
        println(l)
        println(f)
        println(d)
      }

      val closure = () => {
        println(getX)
      }
      // scalastyle:on println
      ClosureCleaner.clean(closure)
    }
    nestedClosure()
  }
}
