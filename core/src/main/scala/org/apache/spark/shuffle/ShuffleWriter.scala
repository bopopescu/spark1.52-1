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

import java.io.IOException

import org.apache.spark.scheduler.MapStatus

/**
 * Shuffle Map Task通过ShuffleWriter将Shuffle数据写入本地。
 * 这个Writer主要通过ShuffleBlockManager来写入数据,因此它的功能是比较轻量级的
 * Obtained inside a map task to write out records to the shuffle system.
  * 在Map任务中获取记录写入Shuffle系统
 */
private[spark] abstract class ShuffleWriter[K, V] {
  /** 
   *  Write a sequence of records to this task's output 
   *  写入所有的数据,需要注意的是如果需要在Map端做聚合(aggregate),那么写入前需要将records做聚合
   *  */
  @throws[IOException]
  def write(records: Iterator[Product2[K, V]]): Unit

  /** 
   *  Close this writer, passing along whether the map completed 
   *  写入完成后提交本次写入
   *  */
  def stop(success: Boolean): Option[MapStatus]
}
