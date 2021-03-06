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

package org.apache.spark.api.python

import java.io.{File}
import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.SparkContext
import org.apache.spark.api.java.{JavaSparkContext, JavaRDD}

private[spark] object PythonUtils {
  /** Get the PYTHONPATH for PySpark, either from SPARK_HOME, if it is set, or from our JAR */
  def sparkPythonPath: String = {
    val pythonPath = new ArrayBuffer[String]
    //System.getenv()和System.getProperties()的区别
    //System.getenv() 返回系统环境变量值 设置系统环境变量：当前登录用户主目录下的".bashrc"文件中可以设置系统环境变量
    //System.getProperties() 返回Java进程变量值 通过命令行参数的"-D"选项
    for (sparkHome <- sys.env.get("SPARK_HOME")) {
      pythonPath += Seq(sparkHome, "python", "lib", "pyspark.zip").mkString(File.separator)
      pythonPath += Seq(sparkHome, "python", "lib", "py4j-0.8.2.1-src.zip").mkString(File.separator)
    }
    pythonPath ++= SparkContext.jarOfObject(this)
    pythonPath.mkString(File.pathSeparator)
  }

  /** Merge PYTHONPATHS with the appropriate separator. Ignores blank strings. */
  def mergePythonPaths(paths: String*): String = {
    paths.filter(_ != "").mkString(File.pathSeparator)
  }

  def generateRDDWithNull(sc: JavaSparkContext): JavaRDD[String] = {
    sc.parallelize(List("a", null, "b"))
  }

  /**
   * Convert list of T into seq of T (for calling API with varargs)
   */
  def toSeq[T](vs: JList[T]): Seq[T] = {
    vs.toList.toSeq
  }

  /**
   * Convert list of T into array of T (for calling API with array)
   */
  def toArray[T](vs: JList[T]): Array[T] = {
    vs.toArray().asInstanceOf[Array[T]]
  }

  /**
   * Convert java map of K, V into Map of K, V (for calling API with varargs)
   */
  def toScalaMap[K, V](jm: java.util.Map[K, V]): Map[K, V] = {
    jm.toMap
  }
}
