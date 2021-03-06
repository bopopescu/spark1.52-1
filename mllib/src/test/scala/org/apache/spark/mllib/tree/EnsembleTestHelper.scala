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

package org.apache.spark.mllib.tree

import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.model.TreeEnsembleModel
import org.apache.spark.util.StatCounter

import scala.collection.mutable

object EnsembleTestHelper {

  /**
   * Aggregates all values in data, and tests whether the empirical mean and stddev are within
   * epsilon of the expected values.
   * 聚合数据中的所有值,和测试均值和标准差期望值
   * @param data  Every element of the data should be an i.i.d. sample from some distribution.
   *  data 数据的每一个元素应该是一个独立同分布的样本分布
   */
  def testRandomArrays(
      data: Array[Array[Double]],
      numCols: Int,
      expectedMean: Double,
      expectedStddev: Double,
      //epsilon代收敛的阀值
      epsilon: Double) {
    val values = new mutable.ArrayBuffer[Double]()
    data.foreach { row =>
      assert(row.size == numCols)
      values ++= row
    }
    val stats = new StatCounter(values)
     //math.abs返回数的绝对值,mean,平均数
    assert(math.abs(stats.mean - expectedMean) < epsilon)
    //stdev 标准差
    assert(math.abs(stats.stdev - expectedStddev) < epsilon)
  }

  def validateClassifier(
      model: TreeEnsembleModel,
      input: Seq[LabeledPoint],
      requiredAccuracy: Double) {
    val predictions = input.map(x => model.predict(x.features))
    val numOffPredictions = predictions.zip(input).count { case (prediction, expected) =>
      prediction != expected.label
    }
    val accuracy = (input.length - numOffPredictions).toDouble / input.length
    assert(accuracy >= requiredAccuracy,
      s"validateClassifier calculated accuracy $accuracy but required $requiredAccuracy.")
  }

  /**
   * Validates a tree ensemble model for regression.
   * 验证回归的树集合模型
   */
  def validateRegressor(
      model: TreeEnsembleModel,
      input: Seq[LabeledPoint],
      required: Double,
      metricName: String = "mse") {
    val predictions = input.map(x => model.predict(x.features))
    val errors = predictions.zip(input.map(_.label)).map { case (prediction, label) =>
      label - prediction
    }
    val metric = metricName match {
      case "mse" =>
        errors.map(err => err * err).sum / errors.size
      case "mae" =>
        //MAE平均绝对误差是所有单个观测值与算术平均值的偏差的绝对值的平均
       //math.abs返回数的绝对值
        errors.map(math.abs).sum / errors.size
    }

    assert(metric <= required,
      s"validateRegressor calculated $metricName $metric but required $required.")
  }

  def generateOrderedLabeledPoints(numFeatures: Int, numInstances: Int): Array[LabeledPoint] = {
    val arr = new Array[LabeledPoint](numInstances)
    for (i <- 0 until numInstances) {
      val label = if (i < numInstances / 10) {
        0.0
      } else if (i < numInstances / 2) {
        1.0
      } else if (i < numInstances * 0.9) {
        0.0
      } else {
        1.0
      }
      val features = Array.fill[Double](numFeatures)(i.toDouble)
      arr(i) = new LabeledPoint(label, Vectors.dense(features))
    }
    arr
  }

}
