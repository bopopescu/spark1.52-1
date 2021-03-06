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

package org.apache.spark.shuffle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import org.mockito.Mockito._
import org.scalatest.concurrent.Timeouts
import org.scalatest.time.SpanSugar._

import org.apache.spark.{SparkConf, SparkFunSuite, TaskContext}
//ShuffleMemoryManager负责管理Shuffle线程占有内存的分配与释放
class ShuffleMemoryManagerSuite extends SparkFunSuite with Timeouts {

  val nextTaskAttemptId = new AtomicInteger()

  /** 
   *  Launch a thread with the given body block and return it. \
   *  用一个给定块启动一个线程并返回
   *  */
  private def startThread(name: String)(body: => Unit): Thread = {
    val thread = new Thread("ShuffleMemorySuite " + name) {
      override def run() {
        try {
          val taskAttemptId = nextTaskAttemptId.getAndIncrement
          val mockTaskContext = mock(classOf[TaskContext], RETURNS_SMART_NULLS)
          when(mockTaskContext.taskAttemptId()).thenReturn(taskAttemptId)
          TaskContext.setTaskContext(mockTaskContext)
          body
        } finally {
          TaskContext.unset()
        }
      }
    }
    thread.start()
    thread
  }
  //ShuffleMemoryManager负责管理Shuffle线程占有内存的分配与释放
  test("single task requesting memory") {//单任务请求存储器
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)
    //尝试获取当前任务的numBytes内存,并返回获得的字节数,如果没有可以分配,则返回0
    assert(manager.tryToAcquire(100L) === 100L)
    assert(manager.tryToAcquire(400L) === 400L)
    assert(manager.tryToAcquire(400L) === 400L)
    assert(manager.tryToAcquire(200L) === 100L)
    assert(manager.tryToAcquire(100L) === 0L)
    assert(manager.tryToAcquire(100L) === 0L)
    //释放部分内存
    manager.release(500L)
    assert(manager.tryToAcquire(300L) === 300L)
    assert(manager.tryToAcquire(300L) === 200L)
    //释放当前任务的所有内存,并将其标记任务结束
    manager.releaseMemoryForThisTask()
    assert(manager.tryToAcquire(1000L) === 1000L)
    assert(manager.tryToAcquire(100L) === 0L)
  }

  test("two threads requesting full memory") {//两个线程请求全部内存
    // Two threads request 500 bytes first, wait for each other to get it, and then request
    // 500 more; we should immediately return 0 as both are now at 1 / N
    //两个线程请求500个字节,等待对方得到它,然后再请求500个,我们应该立即返回0,因为现在都是
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)

    class State {
      var t1Result1 = -1L
      var t2Result1 = -1L
      var t1Result2 = -1L
      var t2Result2 = -1L
    }
    val state = new State

    val t1 = startThread("t1") {
      val r1 = manager.tryToAcquire(500L)
      println("==r1=tryToAcquire="+r1)
      state.synchronized {
        println("==t1==");
        state.t1Result1 = r1
        //notifyAll唤醒obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()

        while (state.t2Result1 === -1L) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          println("===11==")
          state.wait()
          println("===22==")
        }
      }
      val r2 = manager.tryToAcquire(500L)
      println("==r1=="+r2)
      state.synchronized { state.t1Result2 = r2 }
    }

    val t2 = startThread("t2") {
      val r1 = manager.tryToAcquire(500L)
      println("=r2=tryToAcquire="+r1)
      state.synchronized {
        state.t2Result1 = r1
        println("==t2==");
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
        while (state.t1Result1 === -1L) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          println("===33==")
          state.wait()
          println("===44==")
        }
      }
      val r2 = manager.tryToAcquire(500L)
      println("==r2=="+r2)
      state.synchronized { state.t2Result2 = r2 }
    }

    failAfter(20 seconds) {
      t1.join()
      t2.join()
    }

    assert(state.t1Result1 === 500L)
    assert(state.t2Result1 === 500L)
    assert(state.t1Result2 === 0L)
    assert(state.t2Result2 === 0L)
  }


  test("tasks cannot grow past 1 / N") {//任务不能增长过去的1 / N
    // Two tasks request 250 bytes first, wait for each other to get it, and then request
    // 500 more; we should only grant 250 bytes to each of them on this second request
    //两个任务首先请求250个字节，等待对方获取它，然后请求500；在第二个请求中，我们只允许给它们每个字节250个字节。
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)

    class State {
      var t1Result1 = -1L
      var t2Result1 = -1L
      var t1Result2 = -1L
      var t2Result2 = -1L
    }
    val state = new State

    val t1 = startThread("t1") {
      val r1 = manager.tryToAcquire(250L)
      state.synchronized {
        state.t1Result1 = r1
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
        while (state.t2Result1 === -1L) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
      }
      val r2 = manager.tryToAcquire(500L)
      state.synchronized { state.t1Result2 = r2 }
    }

    val t2 = startThread("t2") {
      val r1 = manager.tryToAcquire(250L)
      state.synchronized {
        state.t2Result1 = r1
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
        while (state.t1Result1 === -1L) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
      }
      val r2 = manager.tryToAcquire(500L)
      state.synchronized { state.t2Result2 = r2 }
    }

    failAfter(20 seconds) {
      t1.join()
      t2.join()
    }

    assert(state.t1Result1 === 250L)
    assert(state.t2Result1 === 250L)
    assert(state.t1Result2 === 250L)
    assert(state.t2Result2 === 250L)
  }

  test("tasks can block to get at least 1 / 2N memory") {//任务可以得到至少1／2N的内存
    // t1 grabs 1000 bytes and then waits until t2 is ready to make a request. It sleeps
    // for a bit and releases 250 bytes, which should then be granted to t2. Further requests
    // by t2 will return false right away because it now has 1 / 2N of the memory.
    // t1抓取1000字节，然后等待，直到t2准备好发出请求。 睡了一点，释放250个字节，然后应该被授予t2。 进一步要求
    //由t2将立即返回false，因为它现在有1 / 2N的内存。
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)

    class State {
      var t1Requested = false
      var t2Requested = false
      var t1Result = -1L
      var t2Result = -1L
      var t2Result2 = -1L
      var t2WaitTime = 0L
    }
    val state = new State

    val t1 = startThread("t1") {
      state.synchronized {
        state.t1Result = manager.tryToAcquire(1000L)
        state.t1Requested = true
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
        while (!state.t2Requested) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
      }
      // Sleep a bit before releasing our memory; this is hacky but it would be difficult to make
      // sure the other thread blocks for some time otherwise
      ///在释放我们的记忆之前睡一会儿 这是黑客，但很难做到否则肯定其他线程阻塞一段时间
      Thread.sleep(300)
      manager.release(250L)
    }

    val t2 = startThread("t2") {
      state.synchronized {
        while (!state.t1Requested) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
        state.t2Requested = true
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
      }
      val startTime = System.currentTimeMillis()
      val result = manager.tryToAcquire(250L)
      val endTime = System.currentTimeMillis()
      state.synchronized {
        state.t2Result = result
        // A second call should return 0 because we're now already at 1 / 2N
        //第二次调用应该返回0，因为我们现在已经是1 / 2N
        state.t2Result2 = manager.tryToAcquire(100L)
        state.t2WaitTime = endTime - startTime
      }
    }

    failAfter(20 seconds) {
      t1.join()
      t2.join()
    }

    // Both threads should've been able to acquire their memory; the second one will have waited
    // until the first one acquired 1000 bytes and then released 250
    // 这两个线程都应该能够获取它们的内存; 第二个将等待直到第一个获得1000字节，然后释放250
    state.synchronized {
      assert(state.t1Result === 1000L, "t1 could not allocate memory")
      assert(state.t2Result === 250L, "t2 could not allocate memory")
      assert(state.t2WaitTime > 200, s"t2 waited less than 200 ms (${state.t2WaitTime})")
      assert(state.t2Result2 === 0L, "t1 got extra memory the second time")
    }
  }

  test("releaseMemoryForThisTask") {
    // t1 grabs 1000 bytes and then waits until t2 is ready to make a request. It sleeps
    // for a bit and releases all its memory. t2 should now be able to grab all the memory.
    // t1抓取1000字节，然后等待，直到t2准备好发出请求。 睡了一点点，释放所有的内存。 t2现在应该能够抓住所有的记忆。
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)

    class State {
      var t1Requested = false
      var t2Requested = false
      var t1Result = -1L
      var t2Result1 = -1L
      var t2Result2 = -1L
      var t2Result3 = -1L
      var t2WaitTime = 0L
    }
    val state = new State

    val t1 = startThread("t1") {
      state.synchronized {
        state.t1Result = manager.tryToAcquire(1000L)
        state.t1Requested = true
        //notifyAll唤醒obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
        while (!state.t2Requested) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
      }
      // Sleep a bit before releasing our memory; this is hacky but it would be difficult to make
      // sure the other task blocks for some time otherwise
      //在释放我们的记忆之前睡一会儿 这是黑客，但很难做到则其他任务会阻塞一段时间
      Thread.sleep(300)
      manager.releaseMemoryForThisTask()
    }

    val t2 = startThread("t2") {
      state.synchronized {
        while (!state.t1Requested) {
          //A线程执行obj.wait()方法后,它将释放其所占有的对象锁,A线程进入阻塞状态,等待呗唤醒
          //同时A也就不具有了获得obj对象所的权力,这样其它线程就可以拿到这把锁了
          state.wait()
        }
        state.t2Requested = true
        //notifyAll唤醒因obj对象而阻塞的所有线程,并允许它们有获得对象所的权力
        state.notifyAll()
      }
      val startTime = System.currentTimeMillis()
      val r1 = manager.tryToAcquire(500L)
      val endTime = System.currentTimeMillis()
      val r2 = manager.tryToAcquire(500L)
      val r3 = manager.tryToAcquire(500L)
      state.synchronized {
        state.t2Result1 = r1
        state.t2Result2 = r2
        state.t2Result3 = r3
        state.t2WaitTime = endTime - startTime
      }
    }

    failAfter(20 seconds) {
      t1.join()
      t2.join()
    }

    // Both tasks should've been able to acquire their memory; the second one will have waited
    // until the first one acquired 1000 bytes and then released all of it
    //这两个任务都应该能够获得记忆; 第二个将等待直到第一个获取了1000个字节，然后释放它
    state.synchronized {
      assert(state.t1Result === 1000L, "t1 could not allocate memory")
      assert(state.t2Result1 === 500L, "t2 didn't get 500 bytes the first time")
      assert(state.t2Result2 === 500L, "t2 didn't get 500 bytes the second time")
      assert(state.t2Result3 === 0L, s"t2 got more bytes a third time (${state.t2Result3})")
      assert(state.t2WaitTime > 200, s"t2 waited less than 200 ms (${state.t2WaitTime})")
    }
  }
  //不应该被授予负的大小
  test("tasks should not be granted a negative size") {
    val manager = ShuffleMemoryManager.createForTesting(maxMemory = 1000L)
    manager.tryToAcquire(700L)

    val latch = new CountDownLatch(1)
    startThread("t1") {
      manager.tryToAcquire(300L)
      latch.countDown()
    }
    //等到`t1`调用`tryToAcquire`
    latch.await() // Wait until `t1` calls `tryToAcquire`

    val granted = manager.tryToAcquire(300L)
    assert(0 === granted, "granted is negative")
  }
}
