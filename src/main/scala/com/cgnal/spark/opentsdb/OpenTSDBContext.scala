/*
 * Copyright 2016 CGnal S.p.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cgnal.spark.opentsdb

import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time._
import java.util
import java.util.TimeZone

import com.cloudera.sparkts.{ DateTimeIndex, Frequency, TimeSeriesRDD }
import net.opentsdb.core.TSDB
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.{ Connection, Result, Scan }
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.spark.HBaseContext
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{ DataFrame, Row, SQLContext }
import org.apache.spark.streaming.dstream.DStream
import shapeless.Poly1

import scala.collection.mutable.ArrayBuffer
import scala.language.reflectiveCalls

class OpenTSDBContext(hbaseContext: HBaseContext, dateFormat: String = "dd/MM/yyyy HH:mm") extends Serializable {

  var tsdbTable = "tsdb"

  var tsdbUidTable = "tsdb-uid"

  var metricWidth: Byte = 3

  var tagkWidth: Byte = 3

  var tagvWidth: Byte = 3

  def loadTimeSeriesRDD(
    sqlContext: SQLContext,
    startdate: String,
    enddate: String,
    frequency: Frequency,
    metrics: List[(String, Map[String, String])],
    dateFormat: String = this.dateFormat,
    conversionStrategy: ConversionStrategy = ConvertToDouble
  ): TimeSeriesRDD[String] = {

    val simpleDateFormat = new java.text.SimpleDateFormat(dateFormat)
    simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))

    val startDateEpochInMillis: Long = simpleDateFormat.parse(startdate).getTime
    val endDateEpochInInMillis: Long = simpleDateFormat.parse(enddate).getTime - 1

    LocalDateTime.ofInstant(Instant.ofEpochMilli(startDateEpochInMillis), ZoneId.of("Z"))

    val startZoneDate = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(startDateEpochInMillis), ZoneId.of("Z")), ZoneId.of("Z"))
    val endZoneDate = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(endDateEpochInInMillis), ZoneId.of("Z")), ZoneId.of("Z"))

    var index = DateTimeIndex.uniformFromInterval(startZoneDate, endZoneDate, frequency, ZoneId.of("UTC"))
    index = index.atZone(ZoneId.of("UTC"))

    val dfs: List[DataFrame] = metrics.map(m => loadDataFrame(
      sqlContext,
      m._1,
      m._2,
      Some(startdate),
      Some(enddate),
      dateFormat,
      ConvertToDouble
    ))

    val initDF = dfs.headOption.get
    val otherDFs = dfs.drop(1)

    val observations = if (otherDFs.isEmpty)
      initDF
    else
      otherDFs.fold(initDF)((df1, df2) => df1.unionAll(df2))

    TimeSeriesRDD.timeSeriesRDDFromObservations(
      index,
      observations,
      "timestamp",
      "key",
      "value"
    )
  }

  def loadDataFrame(
    sqlContext: SQLContext,
    metricName: String,
    tags: Map[String, String] = Map.empty[String, String],
    startdate: Option[String] = None,
    enddate: Option[String] = None,
    dateFormat: String = this.dateFormat,
    conversionStrategy: ConversionStrategy = ConvertToDouble,
    metricsUids: Option[Map[String, String]] = None,
    keyIds: Option[Map[String, String]] = None,
    valuesId: Option[Map[String, String]] = None
  ): DataFrame = {
    assert(conversionStrategy != NoConversion) //TODO better error handling
    assert(metricsUids.isDefined && keyIds.isDefined && valuesId.isDefined || metricsUids.isEmpty && keyIds.isEmpty && valuesId.isEmpty) //TODO better error handling
    val schema = StructType(
      Array(
        StructField("timestamp", TimestampType, nullable = false),
        StructField("key", StringType, nullable = false),
        conversionStrategy match {
          case ConvertToFloat => StructField("value", FloatType, nullable = false)
          case ConvertToDouble => StructField("value", DoubleType, nullable = false)
          case NoConversion => throw new Exception("") //TODO better error handling
        },
        StructField("kvids", DataTypes.createMapType(StringType, StringType), nullable = false)
      )
    )

    val (mids, kids, vids) = if (metricsUids.isEmpty) {
      val tsdbUID = hbaseContext.hbaseRDD(TableName.valueOf(tsdbUidTable), new Scan().addFamily("id".getBytes)).asInstanceOf[RDD[(ImmutableBytesWritable, Result)]]
      val mids = tsdbUID.map(l => (l._1.copyBytes, l._2.getValue("id".getBytes, "metrics".getBytes))).filter(p => p._2 != null).collect.map(p => (p._2.mkString, Bytes.toString(p._1))).toMap
      val kids = tsdbUID.map(l => (l._1.copyBytes, l._2.getValue("id".getBytes, "tagk".getBytes))).filter(p => p._2 != null).collect.map(p => (p._2.mkString, Bytes.toString(p._1))).toMap
      val vids = tsdbUID.map(l => (l._1.copyBytes, l._2.getValue("id".getBytes, "tagv".getBytes))).filter(p => p._2 != null).collect.map(p => (p._2.mkString, Bytes.toString(p._1))).toMap
      (mids, kids, vids)
    } else
      (metricsUids.get, keyIds.get, valuesId.get)

    val bmids = sqlContext.sparkContext.broadcast(mids)
    val bkids = sqlContext.sparkContext.broadcast(kids)
    val bvids = sqlContext.sparkContext.broadcast(vids)

    val rowRDD = load(metricName, tags, startdate, enddate, dateFormat, conversionStrategy).mapPartitions(iterator => {
      iterator.map(row => Row(
        row.get(0),
        bmids.value(row.getAs[Array[Byte]](1).mkString),
        row.get(2),
        row.getAs[Map[Array[Byte], Array[Byte]]](3).map(p => (bkids.value(p._1.mkString), bvids.value(p._2.mkString))): Map[String, String]
      ))
    }, preservesPartitioning = true)

    sqlContext.createDataFrame(rowRDD, schema)
  }

  def load(
    metricName: String,
    tags: Map[String, String] = Map.empty[String, String],
    startdate: Option[String] = None,
    enddate: Option[String] = None,
    dateFormat: String = this.dateFormat,
    conversionStrategy: ConversionStrategy = NoConversion
  ): RDD[Row] = {

    val uidScan = getUIDScan(metricName, tags)
    val tsdbUID = hbaseContext.hbaseRDD(TableName.valueOf(tsdbUidTable), uidScan).asInstanceOf[RDD[(ImmutableBytesWritable, Result)]]
    val metricsUID: Array[Array[Byte]] = tsdbUID.map(l => l._2.getValue("id".getBytes, "metrics".getBytes())).filter(_ != null).collect
    val (tagKUIDs, tagVUIDs) = if (tags.isEmpty)
      (Map.empty[String, Array[Byte]], Map.empty[String, Array[Byte]])
    else {
      (
        tsdbUID.map(l => (new String(l._1.copyBytes), l._2.getValue("id".getBytes, "tagk".getBytes))).filter(_._2 != null).collect.toMap,
        tsdbUID.map(l => (new String(l._1.copyBytes), l._2.getValue("id".getBytes, "tagv".getBytes))).filter(_._2 != null).collect.toMap
      )
    }
    if (metricsUID.length == 0)
      throw new Exception(s"Metric not found: $metricName")
    val metricScan = getMetricScan(
      tags,
      metricsUID,
      tagKUIDs,
      tagVUIDs,
      startdate,
      enddate,
      dateFormat
    )

    val tsdb = hbaseContext.hbaseRDD(TableName.valueOf(tsdbTable), metricScan).asInstanceOf[RDD[(ImmutableBytesWritable, Result)]]
    val ts0 = hbaseContext.mapPartitions(tsdb, (iterator: Iterator[(ImmutableBytesWritable, Result)], conn: Connection) => {
      iterator.map {
        kv =>
          val keyBytes = kv._1.copyBytes()
          val chunks = util.Arrays.copyOfRange(keyBytes, metricWidth + 4, keyBytes.length).grouped(tagkWidth + tagvWidth)
          val kvids = chunks.map(chunk => (chunk.take(tagkWidth.toInt), chunk.drop(tagkWidth.toInt))).toMap
          val metricid = keyBytes.take(metricWidth.toInt)
          (util.Arrays.copyOfRange(keyBytes, 3, 7), kv._2.getFamilyMap("t".getBytes()), metricid, kvids)
      }
    }).asInstanceOf[RDD[(Array[Byte], util.NavigableMap[Array[Byte], Array[Byte]], Array[Byte], Map[Array[Byte], Array[Byte]])]]
    val ts1: RDD[Row] = ts0.map(
      kv => {
        val rows = new ArrayBuffer[Row]
        val basetime: Long = ByteBuffer.wrap(kv._1).getInt.toLong
        val iterator = kv._2.entrySet().iterator()
        while (iterator.hasNext) {
          val next = iterator.next()
          val a = next.getKey
          val b = next.getValue
          val quantifiers = processQuantifier(a)
          val deltas = quantifiers.map(_._1)
          val values = processValues(quantifiers, b)
          deltas.zip(values).foreach(dv => {
            object ExtractValue extends Poly1 {
              def convert[A <: AnyVal](x: A): AnyVal = {
                conversionStrategy match {
                  case NoConversion => x
                  case ConvertToFloat => x.asInstanceOf[{ def toFloat: Float }].toFloat
                  case ConvertToDouble => x.asInstanceOf[{ def toDouble: Double }].toDouble
                }
              }

              implicit def caseByte = at[Byte](x => rows += Row(new Timestamp(basetime * 1000 + dv._1), kv._3, convert(x), kv._4))

              implicit def caseInt = at[Int](x => rows += Row(new Timestamp(basetime * 1000 + dv._1), kv._3, convert(x), kv._4))

              implicit def caseLong = at[Long](x => rows += Row(new Timestamp(basetime * 1000 + dv._1), kv._3, convert(x), kv._4))

              implicit def caseFloat = at[Float](x => rows += Row(new Timestamp(basetime * 1000 + dv._1), kv._3, convert(x), kv._4))

              implicit def caseDouble = at[Double](x => rows += Row(new Timestamp(basetime * 1000 + dv._1), kv._3, convert(x), kv._4))
            }
            dv._2 map ExtractValue
          })
        }
        rows
      }
    ).flatMap(identity)
    ts1
  }

  def write[T](timeseries: RDD[(String, Long, T, Map[String, String])])(implicit writeFunc: (Iterator[(String, Long, T, Map[String, String])], TSDB) => Unit): Unit = {
    timeseries.foreachPartition(it => {
      TSDBClientManager(hbaseContext, tsdbTable = tsdbTable, tsdbUidTable = tsdbUidTable)
      writeFunc(it, TSDBClientManager.tsdb)
    })
  }

  def streamWrite[T](dstream: DStream[(String, Long, T, Map[String, String])])(implicit writeFunc: (Iterator[(String, Long, T, Map[String, String])], TSDB) => Unit): Unit = {
    dstream.foreachRDD { timeseries =>
      timeseries.foreachPartition { it =>
        TSDBClientManager(hbaseContext, tsdbTable = tsdbTable, tsdbUidTable = tsdbUidTable)
        writeFunc(it, TSDBClientManager.tsdb)
      }
    }
  }

}