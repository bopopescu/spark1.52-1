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

package org.apache.spark.sql

import java.io.ByteArrayOutputStream

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.serializer.{KryoSerializer, JavaSerializer}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{UnsafeRow, UnsafeProjection}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.memory.MemoryAllocator
import org.apache.spark.unsafe.types.UTF8String
/**
 * 不安全行测试
 */
class UnsafeRowSuite extends SparkFunSuite {

  test("UnsafeRow Java serialization") {//java序列不安全的行
    // serializing an UnsafeRow pointing to a large buffer should only serialize the relevant data
    //序列化一个不安全行,一个大缓冲应该只将序列化相关的数据
    val data = new Array[Byte](1024)
    val row = new UnsafeRow
    //
    row.pointTo(data, 1, 16)
    row.setLong(0, 19285)

    val ser = new JavaSerializer(new SparkConf).newInstance()
    val row1 = ser.deserialize[UnsafeRow](ser.serialize(row))
    assert(row1.getLong(0) == 19285)
    assert(row1.getBaseObject().asInstanceOf[Array[Byte]].length == 16)
  }

  test("UnsafeRow Kryo serialization") {//Kryo序列不安全的行
    // serializing an UnsafeRow pointing to a large buffer should only serialize the relevant data
     //序列化一个不安全行,一个大缓冲应该只将序列化相关的数据
    val data = new Array[Byte](1024)
    val row = new UnsafeRow
    row.pointTo(data, 1, 16)
    row.setLong(0, 19285)
    //使用KryoSerializer序列化,反序列化
    val ser = new KryoSerializer(new SparkConf).newInstance()
    val row1 = ser.deserialize[UnsafeRow](ser.serialize(row))
    assert(row1.getLong(0) == 19285)
    assert(row1.getBaseObject().asInstanceOf[Array[Byte]].length == 16)
  }

  test("bitset width calculation") {//bit集合宽度计算
    assert(UnsafeRow.calculateBitSetWidthInBytes(0) === 0)
    assert(UnsafeRow.calculateBitSetWidthInBytes(1) === 8)
    assert(UnsafeRow.calculateBitSetWidthInBytes(32) === 8)
    assert(UnsafeRow.calculateBitSetWidthInBytes(64) === 8)
    assert(UnsafeRow.calculateBitSetWidthInBytes(65) === 16)
    assert(UnsafeRow.calculateBitSetWidthInBytes(128) === 16)
  }

  test("writeToStream") {//写入流
    //把字符串转换UTF8
    val hello=UTF8String.fromString("hello")
    val row = InternalRow.apply(hello, UTF8String.fromString("world"), 123)
    val arrayBackedUnsafeRow: UnsafeRow =
      UnsafeProjection.create(Array[DataType](StringType, StringType, IntegerType)).apply(row)
    assert(arrayBackedUnsafeRow.getBaseObject.isInstanceOf[Array[Byte]])
    //
    val bytesFromArrayBackedRow: Array[Byte] = {
      //字节数组输出流在内存中创建一个字节数组缓冲区,所有发送到输出流的数据保存在该字节数组缓冲区中
      val baos = new ByteArrayOutputStream()      
      arrayBackedUnsafeRow.writeToStream(baos, null)
      baos.toByteArray
    }
    
    val bytesFromOffheapRow: Array[Byte] = {
      val offheapRowPage = MemoryAllocator.UNSAFE.allocate(arrayBackedUnsafeRow.getSizeInBytes)
      try {
        Platform.copyMemory(
          arrayBackedUnsafeRow.getBaseObject,
          arrayBackedUnsafeRow.getBaseOffset,
          offheapRowPage.getBaseObject,
          offheapRowPage.getBaseOffset,
          arrayBackedUnsafeRow.getSizeInBytes
        )
        val offheapUnsafeRow: UnsafeRow = new UnsafeRow()
        offheapUnsafeRow.pointTo(
          offheapRowPage.getBaseObject,
          offheapRowPage.getBaseOffset,
          3, // num fields
          arrayBackedUnsafeRow.getSizeInBytes
        )
        assert(offheapUnsafeRow.getBaseObject === null)
        val baos = new ByteArrayOutputStream()
        val writeBuffer = new Array[Byte](1024)
        offheapUnsafeRow.writeToStream(baos, writeBuffer)
        baos.toByteArray
      } finally {
        MemoryAllocator.UNSAFE.free(offheapRowPage)
      }
    }

    assert(bytesFromArrayBackedRow === bytesFromOffheapRow)
  }
  //在null列上调用getDouble和getFloat
  test("calling getDouble() and getFloat() on null columns") {
    val row = InternalRow.apply(null, null)
    val unsafeRow = UnsafeProjection.create(Array[DataType](FloatType, DoubleType)).apply(row)
    assert(unsafeRow.getFloat(0) === row.getFloat(0))
    assert(unsafeRow.getDouble(1) === row.getDouble(1))
  }
//在null列上调用get(序数,datatype)
  test("calling get(ordinal, datatype) on null columns") {
    val row = InternalRow.apply(null)
    val unsafeRow = UnsafeProjection.create(Array[DataType](NullType)).apply(row)
    for (dataType <- DataTypeTestUtils.atomicTypes) {
      assert(unsafeRow.get(0, dataType) === null)
    }
  }
  //从字节数组创建并从
  test("createFromByteArray and copyFrom") {
    val row = InternalRow(1, UTF8String.fromString("abc"))
    val converter = UnsafeProjection.create(Array[DataType](IntegerType, StringType))
    val unsafeRow = converter.apply(row)

    val emptyRow = UnsafeRow.createFromByteArray(64, 2)
    val buffer = emptyRow.getBaseObject

    emptyRow.copyFrom(unsafeRow)
    assert(emptyRow.getSizeInBytes() === unsafeRow.getSizeInBytes)
    assert(emptyRow.getInt(0) === unsafeRow.getInt(0))
    assert(emptyRow.getUTF8String(1) === unsafeRow.getUTF8String(1))
    // make sure we reuse the buffer.
    //确保我们重用缓冲区
    assert(emptyRow.getBaseObject === buffer)

    // make sure we really copied the input row.
    //确保我们真的复制输入行
    unsafeRow.setInt(0, 2)
    assert(emptyRow.getInt(0) === 1)
    //longString=abcabcabcabcabcabcabcabcabcabcabcab
    val longString = UTF8String.fromString((1 to 100).map(_ => "abc").reduce(_ + _))
    val row2 = InternalRow(3, longString)
    //[0,3,180000012c,6261636261636261,6163626163626163,6362616362616362]
    val unsafeRow2 = converter.apply(row2)

    // make sure we can resize.
    //确保我们可以调整大小
    emptyRow.copyFrom(unsafeRow2)
    assert(emptyRow.getSizeInBytes() === unsafeRow2.getSizeInBytes)
    assert(emptyRow.getInt(0) === 3)
    assert(emptyRow.getUTF8String(1) === longString)
    // make sure we really resized.
    //确保我们真的调整
    assert(emptyRow.getBaseObject != buffer)

    // make sure we can still handle small rows after resize.
    //确保我们仍然可以处理小行后调整大小
    emptyRow.copyFrom(unsafeRow)
    assert(emptyRow.getSizeInBytes() === unsafeRow.getSizeInBytes)
    assert(emptyRow.getInt(0) === unsafeRow.getInt(0))
    assert(emptyRow.getUTF8String(1) === unsafeRow.getUTF8String(1))
  }
  //通过调用getarray返回不安全数组hashCode
  test("calling hashCode on unsafe array returned by getArray(ordinal)") {
    val row = InternalRow.apply(new GenericArrayData(Array(1L)))
    val unsafeRow = UnsafeProjection.create(Array[DataType](ArrayType(LongType))).apply(row)
    // Makes sure hashCode on unsafe array won't crash
    //确保hashCode不安全的阵列不会崩溃
    unsafeRow.getArray(0).hashCode()
  }
}
