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

import java.nio.ByteBuffer
import java.nio.ByteOrder

import scala.util.Random

import oblivious_sort.ObliviousSort
import org.apache.log4j.Level
import org.apache.log4j.LogManager
import org.apache.spark.unsafe.types.UTF8String

import org.apache.spark.sql.QEDOpcode._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.Block
import org.apache.spark.sql.execution.ConvertToBlocks
import org.apache.spark.sql.functions.avg
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.functions.substring
import org.apache.spark.sql.functions.sum
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.BinaryType
import org.apache.spark.sql.types.DateType
import org.apache.spark.sql.types.FloatType
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType

class QEDSuite extends QueryTest with SharedSQLContext {
  import testImplicits._

  import QED.time

  LogManager.getLogger(classOf[org.apache.spark.scheduler.TaskSetManager]).setLevel(Level.ERROR)
  LogManager.getLogger(classOf[org.apache.spark.storage.BlockManager]).setLevel(Level.ERROR)

  def byte_to_int(array: Array[Byte], index: Int) = {
    val int_bytes = array.slice(index, index + 4)
    val buf = ByteBuffer.wrap(int_bytes)
    buf.order(ByteOrder.LITTLE_ENDIAN)
    buf.getInt
  }

  def byte_to_string(array: Array[Byte], index: Int, length: Int) = {
    val string_bytes = array.slice(index, index + length)
    new String(string_bytes)
  }

  ignore("pagerank") {
    QEDBenchmark.pagerank(sqlContext, "256")
  }

  ignore("big data 1") {
    val answer = QEDBenchmark.bd1SparkSQL(sqlContext, "tiny").collect
    assert(answer === QEDBenchmark.bd1Opaque(sqlContext, "tiny").collect)
    assert(answer === QEDBenchmark.bd1Encrypted(sqlContext, "tiny").collect)
  }

  ignore("big data 2") {
    val answer = QEDBenchmark.bd2SparkSQL(sqlContext, "tiny").sortBy(_._1).map {
      case (str: String, f: Float) => (str, "%.2f".format(f))
    }

    val opaque = QEDBenchmark.bd2Opaque(sqlContext, "tiny").map {
      case (str: String, f: Float) => (str, "%.2f".format(f))
    }
    assert(answer === opaque)

    val encrypted = QEDBenchmark.bd2Encrypted(sqlContext, "tiny").map {
      case (str: String, f: Float) => (str, "%.2f".format(f))
    }
    assert(answer === encrypted)
  }

  ignore("big data 3") {
    val answer = QEDBenchmark.bd3SparkSQL(sqlContext, "tiny")
    assert(answer === QEDBenchmark.bd3Opaque(sqlContext, "tiny"))
    assert(answer === QEDBenchmark.bd3Encrypted(sqlContext, "tiny"))
  }

  ignore("TPC-H query 9") {
    val a = QEDBenchmark.tpch9SparkSQL(sqlContext, "sf_small").sorted
    val b = QEDBenchmark.tpch9Generic(sqlContext, "sf_small").sorted
    val c = QEDBenchmark.tpch9Opaque(sqlContext, "sf_small").sorted
    assert(a.size === b.size)
    assert(a.map { case (a, b, c) => (a, b)} === b.map { case (a, b, c) => (a, b)})
    assert(a.size === c.size)
    assert(a.map { case (a, b, c) => (a, b)} === c.map { case (a, b, c) => (a, b)})
  }

  ignore("columnsort padding") {
    val data = Random.shuffle((0 until 3).map(x => (x.toString, x)).toSeq)
    val encData = QED.encryptN(data).map {
      case Array(str, x) => InternalRow(str, x).encSerialize
    }
    val sorted = ObliviousSort.ColumnSort(
      sparkContext, sparkContext.makeRDD(encData, 1), OP_SORT_COL2)
      .map(row => QED.parseRow(row)).collect
    assert(QED.decrypt2[String, Int](sorted) === data.sortBy(_._2))
  }

  ignore("columnsort on join rows") {
    val p_data = for (i <- 1 to 16) yield (i.toString, i * 10)
    val f_data = for (i <- 1 to 256 - 16) yield ((i % 16).toString, (i * 10).toString, i.toFloat)
    val p = sparkContext.makeRDD(QED.encryptN(p_data), 5)
    val f = sparkContext.makeRDD(QED.encryptN(f_data), 5)
    val j = p.zipPartitions(f) { (pIter, fIter) =>
      val (enclave, eid) = QED.initEnclave()
      val pArr = pIter.toArray
      val fArr = fIter.toArray
      val p = QED.createBlock(
        pArr.map(r => InternalRow.fromSeq(r)).map(_.encSerialize), false)
      val f = QED.createBlock(
        fArr.map(r => InternalRow.fromSeq(r)).map(_.encSerialize), false)
      val r = enclave.JoinSortPreprocess(
        eid, OP_JOIN_COL1.value, p, pArr.length, f, fArr.length)
      Iterator(Block(r, pArr.length + fArr.length))
    }
    val sorted = ObliviousSort.sortBlocks(j, OP_JOIN_COL1).flatMap { block =>
      QED.splitBlock(block.bytes, block.numRows, true)
        .map(serRow => Row.fromSeq(QED.parseRow(serRow)))
    }
    assert(sorted.collect.length === p_data.length + f_data.length)
  }

  ignore("encFilter") {
    val data = for (i <- 0 until 5) yield ("foo", i)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("word", StringType),
        StructField("count", IntegerType))))
    // assert(QED.decrypt2[String, Int](words.encCollect) === data) // TODO

    val filtered = words.encFilter($"count" > lit(3))
    assert(QED.decrypt2[String, Int](filtered.encCollect).sorted === data.filter(_._2 > 3).sorted)
  }

  ignore("encFilter on date") {
    import java.sql.Date
    val dates = List("1975-01-01", "1980-01-01", "1980-03-02", "1980-04-01", "1990-01-01")
    val filteredDates = List("1980-01-01", "1980-03-02", "1980-04-01")
    val javaDates = dates.map(d =>
      DateTimeUtils.toJavaDate(DateTimeUtils.stringToDate(UTF8String.fromString(d)).get))
    val schema = StructType(Seq(StructField("date", DateType)))
    val data = sqlContext.createDataFrame(sparkContext.makeRDD(javaDates.map(Row(_)), 1), schema)
    val filtered = data.filter($"date" >= lit("1980-01-01") && $"date" <= lit("1980-04-01"))
    assert(filtered.collect.map(_.get(0).toString).sorted === filteredDates.sorted)

    val encDates = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(javaDates.map(Tuple1(_))), 1), schema)
    val encFiltered = encDates.encFilter(
      $"date" >= lit("1980-01-01") && $"date" <= lit("1980-04-01"))
    assert(QED.decrypt1[java.sql.Date](encFiltered.encCollect).map(_.toString).sorted ===
      filteredDates.sorted)
  }

  ignore("nonObliviousFilter") {
    val data = for (i <- 0 until 256) yield ("foo", i)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("word", StringType),
        StructField("count", IntegerType))))
    // assert(QED.decrypt2(words.encCollect) === data) // TODO

    val filtered = words.nonObliviousFilter($"count" > lit(3))
    assert(QED.decrypt2[String, Int](filtered.encCollect).sorted === data.filter(_._2 > 3).sorted)
  }

  ignore("encPermute") {
    val array = (0 until 256).toArray
    val permuted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encrypt1(array), 1),
      StructType(Seq(StructField("x", IntegerType))))
      .encPermute().encCollect
    assert(QED.decrypt1[Int](permuted) !== array)
    assert(QED.decrypt1[Int](permuted).sorted === array)
  }

  ignore("nonObliviousAggregate") {
    def abc(i: Int): String = (i % 3) match {
      case 0 => "A"
      case 1 => "B"
      case 2 => "C"
    }
    val data = for (i <- 0 until 256) yield (abc(i), 1)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("word", StringType),
        StructField("count", IntegerType))))

    val summed = words.groupBy($"word").nonObliviousAgg(sum("count").as("totalCount"))
    assert(QED.decrypt2[String, Int](summed.encCollect) ===
      data.groupBy(_._1).mapValues(_.map(_._2).sum).toSeq.sorted)
  }

  ignore("encAggregate") {
    def abc(i: Int): String = (i % 3) match {
      case 0 => "A"
      case 1 => "B"
      case 2 => "C"
    }
    val data = for (i <- 0 until 256) yield (i, abc(i), 1)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("word", StringType),
        StructField("count", IntegerType))))

    val summed = words.groupBy("word").encAgg(sum("count").as("totalCount"))
    assert(QED.decrypt2[String, Int](summed.encCollect) ===
      data.map(p => (p._2, p._3)).groupBy(_._1).mapValues(_.map(_._2).sum).toSeq.sorted)
  }

  ignore("encAggregate - final run split across multiple partitions") {
    val data = for (i <- 0 until 256) yield (i, "A", 1)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 2),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("word", StringType),
        StructField("count", IntegerType))))

    val summed = words.groupBy("word").encAgg(sum("count").as("totalCount"))
    assert(QED.decrypt2[String, Int](summed.encCollect) ===
      data.map(p => (p._2, p._3)).groupBy(_._1).mapValues(_.map(_._2).sum).toSeq.sorted)
  }

  ignore("encAggregate on multiple columns") {
    def abc(i: Int): String = (i % 3) match {
      case 0 => "A"
      case 1 => "B"
      case 2 => "C"
    }
    val data = for (i <- 0 until 256) yield (abc(i), 1, 1.0f)
    val words = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("str", StringType),
        StructField("x", IntegerType),
        StructField("y", FloatType))))

    val summed = words.groupBy("str").encAgg(avg("x").as("avgX"), sum("y").as("totalY"))
    assert(QED.decrypt3[String, Int, Float](summed.encCollect) ===
      data.groupBy(_._1).mapValues(group =>
        (group.map(_._2).sum / group.map(_._2).size, group.map(_._3).sum))
      .toSeq.map { case (str, (avgX, avgY)) => (str, avgX, avgY) }.sorted)
  }

  ignore("encSort") {
    val data = Random.shuffle((0 until 256).map(x => (x.toString, x)).toSeq)
    val sorted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("str", StringType),
        StructField("x", IntegerType))))
      .encSort($"x").encCollect
    assert(QED.decrypt2[String, Int](sorted) === data.sortBy(_._2))
  }

  ignore("nonObliviousSort") {
    val data = Random.shuffle((0 until 256).map(x => (x.toString, x)).toSeq)
    val sorted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("str", StringType),
        StructField("x", IntegerType))))
      .nonObliviousSort($"x").encCollect
    assert(QED.decrypt2[String, Int](sorted) === data.sortBy(_._2))
  }

  ignore("encSort by float") {
    val data = Random.shuffle((0 until 256).map(x => (x.toString, x.toFloat)).toSeq)
    val sorted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("str", StringType),
        StructField("x", FloatType))))
      .encSort($"x").encCollect
    assert(QED.decrypt2[String, Float](sorted) === data.sortBy(_._2))
  }

  ignore("encSort multiple partitions") {
    val data = Random.shuffle(for (i <- 0 until 256) yield (i, i.toString, 1))
    val sorted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 3),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("word", StringType),
        StructField("count", IntegerType))))
      .encSort($"word").encCollect
    assert(QED.decrypt3[Int, String, Int](sorted) === data.sortBy(_._2))
  }

  ignore("nonObliviousSort multiple partitions") {
    val data = Random.shuffle(for (i <- 0 until 256) yield (i, i.toString, 1))
    val sorted = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 3),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("word", StringType),
        StructField("count", IntegerType))))
      .nonObliviousSort($"word").encCollect
    assert(QED.decrypt3[Int, String, Int](sorted) === data.sortBy(_._2))
  }

  ignore("encJoin") {
    val p_data = for (i <- 1 to 16) yield (i, i.toString, i * 10)
    val f_data = for (i <- 1 to 256 - 16) yield (i, (i % 16).toString, i * 10)
    val p = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(p_data), 1),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("pk", StringType),
        StructField("x", IntegerType))))
    val f = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(f_data), 1),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("fk", StringType),
        StructField("x", IntegerType))))
    val joined = p.encJoin(f, $"pk" === $"fk").encCollect
    val expectedJoin =
      for {
        (p_id, pk, p_x) <- p_data
        (f_id, fk, f_x) <- f_data
        if pk == fk
      } yield (p_id, pk, p_x, f_id, f_x)
    assert(QED.decrypt5[Int, String, Int, Int, Int](joined).toSet === expectedJoin.toSet)
  }

  ignore("encJoin on column 1") {
    val p_data = for (i <- 1 to 16) yield (i.toString, i * 10)
    val f_data = for (i <- 1 to 256 - 16) yield ((i % 16).toString, (i * 10).toString, i.toFloat)
    val p = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(p_data), 1),
      StructType(Seq(
        StructField("pk", StringType),
        StructField("x", IntegerType))))
    val f = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(f_data), 1),
      StructType(Seq(
        StructField("fk", StringType),
        StructField("x", StringType),
        StructField("y", FloatType))))
    val joined = p.encJoin(f, $"pk" === $"fk").encCollect
    val expectedJoin =
      for {
        (pk, p_x) <- p_data
        (fk, f_x, f_y) <- f_data
        if pk == fk
      } yield (pk, p_x, f_x, f_y)
    assert(QED.decrypt4[String, Int, String, Float](joined).toSet === expectedJoin.toSet)
  }

  ignore("nonObliviousJoin") {
    val p_data = for (i <- 1 to 16) yield (i.toString, i * 10)
    val f_data = for (i <- 1 to 256 - 16) yield ((i % 16).toString, (i * 10).toString, i.toFloat)
    val p = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(p_data), 1),
      StructType(Seq(
        StructField("pk", StringType),
        StructField("x", IntegerType))))
    val f = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(f_data), 1),
      StructType(Seq(
        StructField("fk", StringType),
        StructField("x", StringType),
        StructField("y", FloatType))))
    val joined = p.nonObliviousJoin(f, $"pk" === $"fk").encCollect
    val expectedJoin =
      for {
        (pk, p_x) <- p_data
        (fk, f_x, f_y) <- f_data
        if pk == fk
      } yield (pk, p_x, f_x, f_y)
    assert(QED.decrypt4[String, Int, String, Float](joined).toSet === expectedJoin.toSet)
  }

  ignore("encSelect") {
    val data = for (i <- 0 until 256) yield ("%03d".format(i) * 3, i.toFloat)
    val rdd = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("str", StringType),
        StructField("x", IntegerType))))
    val proj = rdd.encSelect(substring($"str", 0, 8), $"x")
    assert(QED.decrypt2(proj.encCollect) === data.map { case (str, x) => (str.substring(0, 8), x) })
  }

  ignore("encSelect - pagerank weight * rank") {
    val data = List((1, 2.0f, 3, 4.0f), (2, 0.5f, 1, 2.0f))
    val df = sqlContext.createEncryptedDataFrame(
      sparkContext.makeRDD(QED.encryptN(data), 1),
      StructType(Seq(
        StructField("id", IntegerType),
        StructField("rank", FloatType),
        StructField("dst", IntegerType),
        StructField("weight", FloatType))))
      .encSelect($"dst", $"rank" * $"weight")
    val expected = for ((id, rank, dst, weight) <- data) yield (dst, rank * weight)
    assert(QED.decrypt2(df.encCollect) === expected)
  }

  ignore("JNIEncrypt") {

    def byteArrayToString(x: Array[Byte]) = {
      val loc = x.indexOf(0)
      if (-1 == loc)
        new String(x)
      else if (0 == loc)
        ""
      else
        new String(x, 0, loc, "UTF-8") // or appropriate encoding
    }

    val (enclave, eid) = QED.initEnclave()

    // Test encryption and decryption

    val plaintext = "Hello world!1234"
    val plaintext_bytes = plaintext.getBytes
    val ciphertext = enclave.Encrypt(eid, plaintext_bytes)

    val decrypted = enclave.Decrypt(eid, ciphertext)

    // println("decrypted's length is " + decrypted.length)

    assert(plaintext_bytes.length == decrypted.length)

    for (idx <- 0 to plaintext_bytes.length - 1) {
      assert(plaintext_bytes(idx) == decrypted(idx))
    }
  }

  test("NewColumnSort") {
    val data = Random.shuffle((0 until 256).map(x => (x.toString, x)).toSeq)
    val encData = QED.encryptN(data).map {
      case Array(str, x) => InternalRow(str, x).encSerialize
    }
    val p = QED.createBlock(encData.toArray, false)
    val blocks = new Array[Block](1)
    blocks(0) = Block(p, data.length)

    val result = ObliviousSort.NewColumnSort(sparkContext, sparkContext.makeRDD(blocks, 1), OP_SORT_COL2, 64, 4).flatMap { block =>
      QED.splitBlock(block.bytes, block.numRows, true)
        .map(serRow => QED.parseRow(serRow))}.collect

    assert(QED.decrypt2[String, Int](result) === data.sortBy(_._2))
  }

  test("NewColumnSort -- padding") {
    val data = Random.shuffle((0 until 30).map(x => (x.toString, x)).toSeq)
    val encData = QED.encryptN(data).map {
      case Array(str, x) => InternalRow(str, x).encSerialize
    }
    val p = QED.createBlock(encData.toArray, false)
    val blocks = new Array[Block](1)
    blocks(0) = Block(p, data.length)

    val result = ObliviousSort.NewColumnSort(sparkContext, sparkContext.makeRDD(blocks, 1), OP_SORT_COL2).flatMap { block =>
      QED.splitBlock(block.bytes, block.numRows, true)
        .map(serRow => QED.parseRow(serRow))}.collect

    assert(QED.decrypt2[String, Int](result) === data.sortBy(_._2))
    for (v <- QED.decrypt2[String, Int](result)) {
      println(v)
    }
  }

}
