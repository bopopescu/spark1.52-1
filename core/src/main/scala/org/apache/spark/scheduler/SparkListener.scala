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

package org.apache.spark.scheduler

import java.util.Properties

import scala.collection.Map
import scala.collection.mutable

import org.apache.spark.{Logging, TaskEndReason}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler.cluster.ExecutorInfo
import org.apache.spark.storage.{BlockManagerId, BlockUpdatedInfo}
import org.apache.spark.util.{Distribution, Utils}

@DeveloperApi
sealed trait SparkListenerEvent

@DeveloperApi
case class SparkListenerStageSubmitted(stageInfo: StageInfo, properties: Properties = null)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerStageCompleted(stageInfo: StageInfo) extends SparkListenerEvent

@DeveloperApi
case class SparkListenerTaskStart(stageId: Int, stageAttemptId: Int, taskInfo: TaskInfo)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerTaskGettingResult(taskInfo: TaskInfo) extends SparkListenerEvent

@DeveloperApi
case class SparkListenerTaskEnd(
    stageId: Int,
    stageAttemptId: Int,
    taskType: String,
    reason: TaskEndReason,
    taskInfo: TaskInfo,
    taskMetrics: TaskMetrics)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerJobStart(
    jobId: Int,
    time: Long,
    stageInfos: Seq[StageInfo],
    properties: Properties = null)
  extends SparkListenerEvent {
  // Note: this is here for backwards-compatibility with older versions of this event which
  // only stored stageIds and not StageInfos:
  //注意：这是为了向后兼容此事件的旧版本,只存储stageIds而不是StageInfos：
  val stageIds: Seq[Int] = stageInfos.map(_.stageId)
}

@DeveloperApi
case class SparkListenerJobEnd(
    jobId: Int,
    time: Long,
    jobResult: JobResult)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerEnvironmentUpdate(environmentDetails: Map[String, Seq[(String, String)]])
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerBlockManagerAdded(time: Long, blockManagerId: BlockManagerId, maxMem: Long)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerBlockManagerRemoved(time: Long, blockManagerId: BlockManagerId)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerUnpersistRDD(rddId: Int) extends SparkListenerEvent

@DeveloperApi
case class SparkListenerExecutorAdded(time: Long, executorId: String, executorInfo: ExecutorInfo)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerExecutorRemoved(time: Long, executorId: String, reason: String)
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerBlockUpdated(blockUpdatedInfo: BlockUpdatedInfo) extends SparkListenerEvent

/**
 * Periodic updates from executors.
  * 执行executors的定期更新
 * @param execId executor id
 * @param taskMetrics sequence of (task id, stage id, stage attempt, metrics)
  *                    序列（任务ID,阶段ID,阶段尝试,度量）
 */
@DeveloperApi
case class SparkListenerExecutorMetricsUpdate(
    execId: String,
    taskMetrics: Seq[(Long, Int, Int, TaskMetrics)])
  extends SparkListenerEvent

@DeveloperApi
case class SparkListenerApplicationStart(
    appName: String,
    appId: Option[String],
    time: Long,
    sparkUser: String,
    appAttemptId: Option[String],
    //None被声明为一个对象,而不是一个类,在没有值的时候,使用None,如果有值可以引用,就使用Some来包含这个值,都是Option的子类
    driverLogs: Option[Map[String, String]] = None) extends SparkListenerEvent

@DeveloperApi
case class SparkListenerApplicationEnd(time: Long) extends SparkListenerEvent

/**
 * An internal class that describes the metadata of an event log.
 * This event is not meant to be posted to listeners downstream.
  * 描述事件日志的元数据的内部类,此事件并不意味着发布到下游的监听器。
 */
private[spark] case class SparkListenerLogStart(sparkVersion: String) extends SparkListenerEvent

/**
 * :: DeveloperApi ::
 * Interface for listening to events from the Spark scheduler. Note that this is an internal
 * interface which might change in different Spark releases. Java clients should extend
  * 用于侦听Spark调度程序中的事件的接口,请注意,这是一个可能在不同Spark版本中更改的内部接口,Java客户端应该扩展
 * {@link JavaSparkListener}
 * Spark 调度程序的事件接口
 */
@DeveloperApi
trait SparkListener {
  /**
   * Called when a stage completes successfully or fails, with information on the completed stage.
   * 当一个阶段完成或失败时，在完成阶段的信息中调用。
   */
  def onStageCompleted(stageCompleted: SparkListenerStageCompleted) { }

  /**
   * Called when a stage is submitted
   * 当调用一个阶段提交时
   */
  def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted) { }

  /**
   * Called when a task starts
   * 当调用一个任务运行时
   */
  def onTaskStart(taskStart: SparkListenerTaskStart) { }

  /**
   * Called when a task begins remotely fetching its result (will not be called for tasks that do
   * not need to fetch the result remotely).
    * 当任务开始时远程调用它的结果(不会为不需要远程获取结果的任务调用)。
   */
  def onTaskGettingResult(taskGettingResult: SparkListenerTaskGettingResult) { }

  /**
   * Called when a task ends
   * 当调用任务结束时
   */
  def onTaskEnd(taskEnd: SparkListenerTaskEnd) { }

  /**
   * Called when a job starts
   * 当调用Job开始运行时
   */
  def onJobStart(jobStart: SparkListenerJobStart) { }

  /**
   * Called when a job ends
   * 当调用job结束时
   */
  def onJobEnd(jobEnd: SparkListenerJobEnd) { }

  /**
   * Called when environment properties have been updated
   * 当环境属性已被更新时
   */
  def onEnvironmentUpdate(environmentUpdate: SparkListenerEnvironmentUpdate) { }

  /**
   * Called when a new block manager has joined
   * 当一个新的块加入块管理器时调用
   */
  def onBlockManagerAdded(blockManagerAdded: SparkListenerBlockManagerAdded) { }

  /**
   * Called when an existing block manager has been removed
   * 当一个现有的块管理器已被删除时调用
   */
  def onBlockManagerRemoved(blockManagerRemoved: SparkListenerBlockManagerRemoved) { }

  /**
   * Called when an RDD is manually unpersisted by the application
    * 当RDD由应用程序手动不分开时调用
   */
  def onUnpersistRDD(unpersistRDD: SparkListenerUnpersistRDD) { }

  /**
   * Called when the application starts
   * 当应用程序启动时调用
   */
  def onApplicationStart(applicationStart: SparkListenerApplicationStart) { }

  /**
   * Called when the application ends
   * 当应用程序终止时调用
   */
  def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd) { }

  /**
   * Called when the driver receives task metrics from an executor in a heartbeat.
   * 当驱动程序从执行器接收到一个心跳中的任务度量时调用
   */
  def onExecutorMetricsUpdate(executorMetricsUpdate: SparkListenerExecutorMetricsUpdate) { }

  /**
   * Called when the driver registers a new executor.
   * 当驱动程序注册一个新的执行器时调用
   */
  def onExecutorAdded(executorAdded: SparkListenerExecutorAdded) { }

  /**
   * Called when the driver removes an executor.
   * 当驱动程序删除执行程序时调用
   */
  def onExecutorRemoved(executorRemoved: SparkListenerExecutorRemoved) { }

  /**
   * Called when the driver receives a block update info.
   * 当驱动程序接收到块更新信息时调用
   */
  def onBlockUpdated(blockUpdated: SparkListenerBlockUpdated) { }
}

/**
 * :: DeveloperApi ::
 * Simple SparkListener that logs a few summary statistics when each stage completes
 * 简单的spark侦听器记录每个Stage完成时统计结果
 */
@DeveloperApi
class StatsReportListener extends SparkListener with Logging {

  import org.apache.spark.scheduler.StatsReportListener._

  private val taskInfoMetrics = mutable.Buffer[(TaskInfo, TaskMetrics)]()

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd) {
    val info = taskEnd.taskInfo
    val metrics = taskEnd.taskMetrics
    if (info != null && metrics != null) {
      taskInfoMetrics += ((info, metrics))
    }
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) {
    implicit val sc = stageCompleted
    this.logInfo("Finished stage: " + stageCompleted.stageInfo)
    showMillisDistribution("task runtime:", (info, _) => Some(info.duration), taskInfoMetrics)

    // Shuffle write
    showBytesDistribution("shuffle bytes written:",
      (_, metric) => metric.shuffleWriteMetrics.map(_.shuffleBytesWritten), taskInfoMetrics)

    // Fetch & I/O 获取和I / O
    showMillisDistribution("fetch wait time:",
      (_, metric) => metric.shuffleReadMetrics.map(_.fetchWaitTime), taskInfoMetrics)
    showBytesDistribution("remote bytes read:",
      (_, metric) => metric.shuffleReadMetrics.map(_.remoteBytesRead), taskInfoMetrics)
    showBytesDistribution("task result size:",
      (_, metric) => Some(metric.resultSize), taskInfoMetrics)

    // Runtime breakdown
    //运行时故障
    val runtimePcts = taskInfoMetrics.map { case (info, metrics) =>
      RuntimePercentage(info.duration, metrics)
    }
    showDistribution("executor (non-fetch) time pct: ",
      Distribution(runtimePcts.map(_.executorPct * 100)), "%2.0f %%")
    showDistribution("fetch wait time pct: ",
      Distribution(runtimePcts.flatMap(_.fetchPct.map(_ * 100))), "%2.0f %%")
    showDistribution("other time pct: ", Distribution(runtimePcts.map(_.other * 100)), "%2.0f %%")
    taskInfoMetrics.clear()
  }

}

private[spark] object StatsReportListener extends Logging {

  // For profiling, the extremes are more interesting
  //对于分析,极端更有趣
  val percentiles = Array[Int](0, 5, 10, 25, 50, 75, 90, 95, 100)
  val probabilities = percentiles.map(_ / 100.0)
  val percentilesHeader = "\t" + percentiles.mkString("%\t") + "%"

  def extractDoubleDistribution(
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)],
      getMetric: (TaskInfo, TaskMetrics) => Option[Double]): Option[Distribution] = {
    Distribution(taskInfoMetrics.flatMap { case (info, metric) => getMetric(info, metric) })
  }

  // Is there some way to setup the types that I can get rid of this completely?
  //有没有办法设置我可以完全摆脱这些类型？
  def extractLongDistribution(
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)],
      getMetric: (TaskInfo, TaskMetrics) => Option[Long]): Option[Distribution] = {
    extractDoubleDistribution(
      taskInfoMetrics,
      (info, metric) => { getMetric(info, metric).map(_.toDouble) })
  }

  def showDistribution(heading: String, d: Distribution, formatNumber: Double => String) {
    val stats = d.statCounter
    val quantiles = d.getQuantiles(probabilities).map(formatNumber)
    logInfo(heading + stats)
    logInfo(percentilesHeader)
    logInfo("\t" + quantiles.mkString("\t"))
  }

  def showDistribution(
      heading: String,
      dOpt: Option[Distribution],
      formatNumber: Double => String) {
    dOpt.foreach { d => showDistribution(heading, d, formatNumber)}
  }

  def showDistribution(heading: String, dOpt: Option[Distribution], format: String) {
    def f(d: Double): String = format.format(d)
    showDistribution(heading, dOpt, f _)
  }

  def showDistribution(
      heading: String,
      format: String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Double],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showDistribution(heading, extractDoubleDistribution(taskInfoMetrics, getMetric), format)
  }

  def showBytesDistribution(
      heading: String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Long],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showBytesDistribution(heading, extractLongDistribution(taskInfoMetrics, getMetric))
  }

  def showBytesDistribution(heading: String, dOpt: Option[Distribution]) {
    dOpt.foreach { dist => showBytesDistribution(heading, dist) }
  }

  def showBytesDistribution(heading: String, dist: Distribution) {
    showDistribution(heading, dist, (d => Utils.bytesToString(d.toLong)): Double => String)
  }

  def showMillisDistribution(heading: String, dOpt: Option[Distribution]) {
    showDistribution(heading, dOpt,
      (d => StatsReportListener.millisToString(d.toLong)): Double => String)
  }

  def showMillisDistribution(
      heading: String,
      getMetric: (TaskInfo, TaskMetrics) => Option[Long],
      taskInfoMetrics: Seq[(TaskInfo, TaskMetrics)]) {
    showMillisDistribution(heading, extractLongDistribution(taskInfoMetrics, getMetric))
  }

  val seconds = 1000L
  val minutes = seconds * 60
  val hours = minutes * 60

  /**
   * Reformat a time interval in milliseconds to a prettier format for output
   * 重新格式化以毫秒为单位的时间间隔的格式输出
   */
  def millisToString(ms: Long): String = {
    val (size, units) =
      if (ms > hours) {
        (ms.toDouble / hours, "hours")
      } else if (ms > minutes) {
        (ms.toDouble / minutes, "min")
      } else if (ms > seconds) {
        (ms.toDouble / seconds, "s")
      } else {
        (ms.toDouble, "ms")
      }
    "%.1f %s".format(size, units)
  }
}

private case class RuntimePercentage(executorPct: Double, fetchPct: Option[Double], other: Double)

private object RuntimePercentage {
  def apply(totalTime: Long, metrics: TaskMetrics): RuntimePercentage = {
    val denom = totalTime.toDouble
    val fetchTime = metrics.shuffleReadMetrics.map(_.fetchWaitTime)
    val fetch = fetchTime.map(_ / denom)
    val exec = (metrics.executorRunTime - fetchTime.getOrElse(0L)) / denom
    val other = 1.0 - (exec + fetch.getOrElse(0d))
    RuntimePercentage(exec, fetch, other)
  }
}
