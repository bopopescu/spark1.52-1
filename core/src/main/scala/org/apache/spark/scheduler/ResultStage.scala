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

import org.apache.spark.rdd.RDD
import org.apache.spark.util.CallSite

/**
 * The ResultStage represents the final stage in a job.
 * 表示Job中的最后阶段(stage)
 */
private[spark] class ResultStage(
    id: Int,//id为stage的id
    rdd: RDD[_],//rdd为stage中最后一个rdd
    numTasks: Int, //任务数
    parents: List[Stage],//父Stage
    firstJobId: Int,
    callSite: CallSite)
  extends Stage(id, rdd, numTasks, parents, firstJobId, callSite) {

  // The active job for this result stage. Will be empty if the job has already finished
  //这是一个活动Job对于这个结果阶段,如果Job已经完成则空,因为这个Job被取消了.
  // (e.g., because the job was cancelled).
  var resultOfJob: Option[ActiveJob] = None

  override def toString: String = "ResultStage " + id
}
