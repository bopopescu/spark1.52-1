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

package org.apache.spark

import scala.collection.mutable.{ ArrayBuffer, HashMap }

import org.apache.spark.executor.TaskMetrics
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.metrics.source.Source
import org.apache.spark.unsafe.memory.TaskMemoryManager
import org.apache.spark.util.{ TaskCompletionListener, TaskCompletionListenerException }

private[spark] class TaskContextImpl(
  val stageId: Int,
  val partitionId: Int,
  override val taskAttemptId: Long,
  override val attemptNumber: Int,
  override val taskMemoryManager: TaskMemoryManager,
  @transient private val metricsSystem: MetricsSystem,
  internalAccumulators: Seq[Accumulator[Long]],
  val runningLocally: Boolean = false,
  val taskMetrics: TaskMetrics = TaskMetrics.empty)
    extends TaskContext
    with Logging {

  // For backwards-compatibility; this method is now deprecated as of 1.3.0.
  //为了向后兼容; 这个方法现在已经从1.3.0开始弃用了。
  override def attemptId(): Long = taskAttemptId

  // List of callback functions to execute when the task completes.
  // 当任务完成时调用的回调函数列表,ArrayBuffer可变数组
  @transient private val onCompleteCallbacks = new ArrayBuffer[TaskCompletionListener]

  // Whether the corresponding task has been killed.
  //是否对应的任务已被杀死。
  @volatile private var interrupted: Boolean = false

  // Whether the task has completed.
  //是否任务已经完成。
  @volatile private var completed: Boolean = false
  //添加一个完成任务的执行的侦听器,这记录任务的成功,失败,或者取消. 
  override def addTaskCompletionListener(listener: TaskCompletionListener): this.type = {
    onCompleteCallbacks += listener
    this
  }

  override def addTaskCompletionListener(f: TaskContext => Unit): this.type = {
    onCompleteCallbacks += new TaskCompletionListener {
      override def onTaskCompletion(context: TaskContext): Unit = f(context)
    }
    this
  }

  @deprecated("use addTaskCompletionListener", "1.1.0")
  override def addOnCompleteCallback(f: () => Unit) {
    onCompleteCallbacks += new TaskCompletionListener {
      override def onTaskCompletion(context: TaskContext): Unit = f()
    }
  }

  /**
   *  Marks the task as completed and triggers the listeners.
   *  标记task任务完成触发listeners
   */
  private[spark] def markTaskCompleted(): Unit = {
    completed = true //标记task完成
    val errorMsgs = new ArrayBuffer[String](2) //记录错误信息
    // Process complete callbacks in the reverse order of registration
    //以相反的注册顺序处理完成回调
    onCompleteCallbacks.reverse.foreach { listener =>
      try {
        listener.onTaskCompletion(this) //执行回调函数
      } catch {
        case e: Throwable => //发生异常,记录错误信息
          errorMsgs += e.getMessage
          logError("Error in TaskCompletionListener", e)
      }
    }
    if (errorMsgs.nonEmpty) { //如果错误信息不为空,那么抛出异常
      throw new TaskCompletionListenerException(errorMsgs)
    }
  }

  /**
   * Marks the task for interruption, i.e. cancellation.
   * 标记任务中断
   */
  private[spark] def markInterrupted(): Unit = {
    interrupted = true
  }
  //如果任务已完成,则返回真 
  override def isCompleted(): Boolean = completed
  //如果任务在驱动程序中运行本地运行,则返回真
  override def isRunningLocally(): Boolean = runningLocally
  //如果任务已被杀死,返回真 
  override def isInterrupted(): Boolean = interrupted

  override def getMetricsSources(sourceName: String): Seq[Source] =
    metricsSystem.getSourcesByName(sourceName)
  //累加器
  @transient private val accumulators = new HashMap[Long, Accumulable[_, _]]

  private[spark] override def registerAccumulator(a: Accumulable[_, _]): Unit = synchronized {
    accumulators(a.id) = a
  }

  private[spark] override def collectInternalAccumulators(): Map[Long, Any] = synchronized {
    accumulators.filter(_._2.isInternal).mapValues(_.localValue).toMap
  }

  private[spark] override def collectAccumulators(): Map[Long, Any] = synchronized {
    accumulators.mapValues(_.localValue).toMap
  }

  private[spark] override val internalMetricsToAccumulators: Map[String, Accumulator[Long]] = {
    // Explicitly register internal accumulators here because these are
    // not captured in the task closure and are already deserialized
    //在这里明确地注册内部的累加器,因为它们没有被捕获在任务关闭中并且已被反序列化
    internalAccumulators.foreach(registerAccumulator)
    internalAccumulators.map { a => (a.name.get, a) }.toMap
  }
}
