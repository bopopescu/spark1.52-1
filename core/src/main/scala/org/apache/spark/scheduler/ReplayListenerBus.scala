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

import java.io.{InputStream, IOException}

import scala.io.Source

import com.fasterxml.jackson.core.JsonParseException
import org.json4s.jackson.JsonMethods._

import org.apache.spark.Logging
import org.apache.spark.util.JsonProtocol

/**
 * A SparkListenerBus that can be used to replay events from serialized event data.
  * SparkListenerBus可用于从序列化事件数据重播事件
 */
private[spark] class ReplayListenerBus extends SparkListenerBus with Logging {

  /**
   * Replay each event in the order maintained in the given stream. The stream is expected to
   * contain one JSON-encoded SparkListenerEvent per line.
    * 按照给定流中维护的顺序重播每个事件,该流预计每行包含一个JSON编码的SparkListenerEvent
   *
   * This method can be called multiple times, but the listener behavior is undefined after any
   * error is thrown by this method.
    * 这个方法可以被多次调用,但是在这种方法抛出任何错误之后,监听器行为是未定义的,
   *
   * @param logData Stream containing event log data.流包含事件日志数据
   * @param sourceName Filename (or other source identifier) from whence @logData is being read
    *                   从读取@logData的文件名(或其他源标识符)
   * @param maybeTruncated Indicate whether log file might be truncated (some abnormal situations
   *        encountered, log file might not finished writing) or not
    *        指示日志文件是否可能被截断(遇到某些异常情况,日志文件可能无法完成写入)
   */
  def replay(
      logData: InputStream,
      sourceName: String,
      maybeTruncated: Boolean = false): Unit = {
    var currentLine: String = null
    var lineNumber: Int = 1
    try {
      val lines = Source.fromInputStream(logData).getLines()
      while (lines.hasNext) {
        currentLine = lines.next()
        try {
          postToAll(JsonProtocol.sparkEventFromJson(parse(currentLine)))
        } catch {
          case jpe: JsonParseException =>
            // We can only ignore exception from last line of the file that might be truncated
            //我们只能忽略可能被截断的文件的最后一行的异常
            if (!maybeTruncated || lines.hasNext) {
              throw jpe
            } else {
              logWarning(s"Got JsonParseException from log file $sourceName" +
                s" at line $lineNumber, the file might not have finished writing cleanly.")
            }
        }
        lineNumber += 1
      }
    } catch {
      case ioe: IOException =>
        throw ioe
      case e: Exception =>
        logError(s"Exception parsing Spark event log: $sourceName", e)
        logError(s"Malformed line #$lineNumber: $currentLine\n")
    }
  }

}
