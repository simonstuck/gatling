/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.charts.result.reader

import java.io.{ FileInputStream, InputStream }

import scala.collection.breakOut
import scala.collection.mutable
import scala.io.Source

import com.typesafe.scalalogging.slf4j.StrictLogging

import io.gatling.charts.result.reader.buffers.{ PercentilesBuffers, CountBuffer, GeneralStatsBuffer }
import io.gatling.charts.result.reader.stats.StatsHelper
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.config.GatlingFiles.simulationLogDirectory
import io.gatling.core.result._
import io.gatling.core.result.message.{ KO, OK, Status }
import io.gatling.core.result.reader.{ DataReader, GeneralStats }
import io.gatling.core.result.writer._
import io.gatling.core.util.DateHelper.parseTimestampString

object FileDataReader {

  val LogStep = 100000
  val SecMillisecRatio = 1000.0
  val NoPlotMagicValue = -1L
  val SimulationFilesNamePattern = """.*\.log"""
}

class FileDataReader(runUuid: String) extends DataReader(runUuid) with StrictLogging {

  import FileDataReader._

  println("Parsing log file(s)...")

  val inputFiles = simulationLogDirectory(runUuid, create = false).files
    .collect { case file if file.name.matches(SimulationFilesNamePattern) => file.jfile }
    .toList

  logger.info(s"Collected $inputFiles from $runUuid")
  require(inputFiles.nonEmpty, "simulation directory doesn't contain any log file.")

  private def doWithInputFiles[T](f: Iterator[String] => T): T = {

      def multipleFileIterator(streams: Seq[InputStream]): Iterator[String] = streams.map(Source.fromInputStream(_)(configuration.core.codec).getLines()).reduce((first, second) => first ++ second)

    val streams = inputFiles.map(new FileInputStream(_))
    try f(multipleFileIterator(streams))
    finally streams.foreach(_.close)
  }

  case class FirstPassData(runStart: Long,
                           runEnd: Long,
                           runMessage: RunMessage,
                           requestResponseTimeMaxValue: Int,
                           groupDurationMaxValue: Int,
                           groupCumulatedResponseTimeMaxValue: Int)

  private def firstPass(records: Iterator[String]): FirstPassData = {

    logger.info("First pass")

    var count = 0

    var runStart = Long.MaxValue
    var runEnd = Long.MinValue

    var maxRequestResponseTime = 0
    var maxGroupDuration = 0
    var maxGroupCumulatedResponseTime = 0

    val runMessages = mutable.ListBuffer.empty[RunMessage]

    records.foreach { line =>
      count += 1
      if (count % LogStep == 0) logger.info(s"First pass, read $count lines")

      line.split(FileDataWriter.Separator) match {

        case RawRequestRecord(array) =>
          val firstByteSent = array(5).toLong
          val lastByteReceived = array(8).toLong

          runStart = math.min(runStart, firstByteSent)
          runEnd = math.max(runEnd, lastByteReceived)

          val responseTime = (lastByteReceived - firstByteSent).toInt
          maxRequestResponseTime = math.max(maxRequestResponseTime, responseTime)

        case RawUserRecord(array) =>
          runStart = math.min(runStart, array(4).toLong)
          runEnd = math.max(runEnd, array(5).toLong)

        case RawGroupRecord(array) =>
          val groupEntry = array(4).toLong
          val groupExit = array(5).toLong

          runStart = math.min(runStart, groupEntry)
          runEnd = math.max(runEnd, groupExit)

          val duration = (groupExit - groupEntry).toInt
          maxGroupDuration = math.max(maxGroupDuration, duration)

          val cumulatedResponseTime = array(6).toInt
          maxGroupCumulatedResponseTime = math.max(maxGroupCumulatedResponseTime, cumulatedResponseTime)

        case RawRunRecord(array) =>
          runMessages += RunMessage(array(0), array(1), parseTimestampString(array(3)), array(4).trim)

        case _ =>
          logger.debug(s"Record broken on line $count: $line")
      }
    }

    logger.info(s"First pass done: read $count lines")

    FirstPassData(
      runStart,
      runEnd,
      runMessages.head,
      maxRequestResponseTime,
      maxGroupDuration,
      maxGroupCumulatedResponseTime)
  }

  val FirstPassData(
    runStart,
    runEnd,
    runMessage,
    requestResponseTimeMaxValue,
    groupDurationMaxValue,
    groupCumulatedResponseTimeMaxValue) = doWithInputFiles(firstPass)

  val step = StatsHelper.step(math.floor(runStart / SecMillisecRatio).toInt, math.ceil(runEnd / SecMillisecRatio).toInt, configuration.charting.maxPlotsPerSeries) * SecMillisecRatio
  val bucketFunction = StatsHelper.bucket(_: Int, 0, (runEnd - runStart).toInt, step, step / 2)
  val buckets = StatsHelper.bucketsList(0, (runEnd - runStart).toInt, step)

  private def secondPass(bucketFunction: Int => Int)(records: Iterator[String]): ResultsHolder = {

    logger.info("Second pass")

    val resultsHolder = new ResultsHolder(runStart, runEnd, requestResponseTimeMaxValue, groupDurationMaxValue, groupCumulatedResponseTimeMaxValue)

    var count = 0

    val requestRecordParser = new RequestRecordParser(bucketFunction, runStart)
    val groupRecordParser = new GroupRecordParser(bucketFunction, runStart)
    val userRecordParser = new UserRecordParser(bucketFunction, runStart)

    records
      .foreach { line =>
        count += 1
        if (count % LogStep == 0) logger.info(s"Second pass, read $count lines")

        line.split(FileDataWriter.Separator) match {
          case requestRecordParser(record) => resultsHolder.addRequestRecord(record)
          case groupRecordParser(record)   => resultsHolder.addGroupRecord(record)
          case userRecordParser(record)    => resultsHolder.addUserRecord(record)
          case _                           =>
        }
      }

    resultsHolder.endOrphanUserRecords(bucketFunction(reduceAccuracy((runEnd - runStart).toInt)))

    logger.info(s"Second pass: read $count lines")

    resultsHolder
  }

  val resultsHolder = doWithInputFiles(secondPass(bucketFunction))

  println("Parsing log file(s) done")

  val statsPaths: List[StatsPath] =
    resultsHolder.groupAndRequestsNameBuffer.map.toList.map {
      case (path @ RequestStatsPath(request, group), time) => (path, (time, group.map(_.hierarchy.size + 1).getOrElse(0)))
      case (path @ GroupStatsPath(group), time) => (path, (time, group.hierarchy.size))
      case _ => throw new UnsupportedOperationException
    }.sortBy(_._2).map(_._1)

  def requestNames: List[String] = statsPaths.collect { case RequestStatsPath(request, _) => request }

  def scenarioNames: List[String] = resultsHolder.scenarioNameBuffer
    .map
    .toList
    .sortBy(_._2)
    .map(_._1)

  def numberOfActiveSessionsPerSecond(scenarioName: Option[String]): Seq[IntVsTimePlot] = resultsHolder
    .getSessionDeltaPerSecBuffers(scenarioName)
    .compute(buckets)

  private def countBuffer2IntVsTimePlots(buffer: CountBuffer): Seq[IntVsTimePlot] = buffer
    .distribution
    .map(plot => plot.copy(value = (plot.value / step * SecMillisecRatio).toInt))
    .toSeq
    .sortBy(_.time)

  def numberOfRequestsPerSecond(status: Option[Status], requestName: Option[String], group: Option[Group]): Seq[IntVsTimePlot] =
    countBuffer2IntVsTimePlots(resultsHolder.getRequestsPerSecBuffer(requestName, group, status))

  def numberOfResponsesPerSecond(status: Option[Status], requestName: Option[String], group: Option[Group]): Seq[IntVsTimePlot] =
    countBuffer2IntVsTimePlots(resultsHolder.getResponsesPerSecBuffer(requestName, group, status))

  private def distribution(maxPlots: Int, allBuffer: GeneralStatsBuffer, okBuffers: GeneralStatsBuffer, koBuffer: GeneralStatsBuffer): (Seq[PercentVsTimePlot], Seq[PercentVsTimePlot]) = {

    // get main and max for request/all status
    val size = allBuffer.stats.count
    val ok = okBuffers.distribution
    val ko = koBuffer.distribution
    val min = allBuffer.stats.min
    val max = allBuffer.stats.max

      def percent(s: Int) = s * 100.0 / size

    if (max - min <= maxPlots) {
        // use exact values
        def plotsToPercents(plots: Iterable[IntVsTimePlot]) = plots.map(plot => PercentVsTimePlot(plot.time, percent(plot.value))).toSeq.sortBy(_.time)
      (plotsToPercents(ok), plotsToPercents(ko))

    } else {
      // use buckets
      val step = StatsHelper.step(min, max, maxPlots)
      val halfStep = step / 2
      val buckets = StatsHelper.bucketsList(min, max, step)

      val bucketFunction = StatsHelper.bucket(_: Int, min, max, step, halfStep)

        def process(buffer: Iterable[IntVsTimePlot]): Seq[PercentVsTimePlot] = {

          val bucketsWithValues: Map[Int, Double] = buffer
            .map(record => (bucketFunction(record.time), record))
            .groupBy(_._1)
            .map {
              case (responseTimeBucket, recordList) =>

                val bucketSize = recordList.foldLeft(0) {
                  (partialSize, record) => partialSize + record._2.value
                }

                (responseTimeBucket, percent(bucketSize))
            }(breakOut)

          buckets.map {
            bucket => PercentVsTimePlot(bucket, bucketsWithValues.getOrElse(bucket, 0.0))
          }
        }

      (process(ok), process(ko))
    }
  }

  def responseTimeDistribution(maxPlots: Int, requestName: Option[String], group: Option[Group]): (Seq[PercentVsTimePlot], Seq[PercentVsTimePlot]) =
    distribution(maxPlots,
      resultsHolder.getRequestGeneralStatsBuffers(requestName, group, None),
      resultsHolder.getRequestGeneralStatsBuffers(requestName, group, Some(OK)),
      resultsHolder.getRequestGeneralStatsBuffers(requestName, group, Some(KO)))

  def groupCumulatedResponseTimeDistribution(maxPlots: Int, group: Group): (Seq[PercentVsTimePlot], Seq[PercentVsTimePlot]) =
    distribution(maxPlots,
      resultsHolder.getGroupCumulatedResponseTimeGeneralStatsBuffers(group, None),
      resultsHolder.getGroupCumulatedResponseTimeGeneralStatsBuffers(group, Some(OK)),
      resultsHolder.getGroupCumulatedResponseTimeGeneralStatsBuffers(group, Some(KO)))

  def groupDurationDistribution(maxPlots: Int, group: Group): (Seq[PercentVsTimePlot], Seq[PercentVsTimePlot]) =
    distribution(maxPlots,
      resultsHolder.getGroupDurationGeneralStatsBuffers(group, None),
      resultsHolder.getGroupDurationGeneralStatsBuffers(group, Some(OK)),
      resultsHolder.getGroupDurationGeneralStatsBuffers(group, Some(KO)))

  def requestGeneralStats(requestName: Option[String], group: Option[Group], status: Option[Status]): GeneralStats = resultsHolder
    .getRequestGeneralStatsBuffers(requestName, group, status)
    .stats

  def groupCumulatedResponseTimeGeneralStats(group: Group, status: Option[Status]): GeneralStats = resultsHolder
    .getGroupCumulatedResponseTimeGeneralStatsBuffers(group, status)
    .stats

  def groupDurationGeneralStats(group: Group, status: Option[Status]): GeneralStats = resultsHolder
    .getGroupDurationGeneralStatsBuffers(group, status)
    .stats

  def numberOfRequestInResponseTimeRange(requestName: Option[String], group: Option[Group]): Seq[(String, Int)] = {

    val counts = resultsHolder.getResponseTimeRangeBuffers(requestName, group)
    val lowerBound = configuration.charting.indicators.lowerBound
    val higherBound = configuration.charting.indicators.higherBound

    List((s"t < $lowerBound ms", counts.low),
      (s"$lowerBound ms < t < $higherBound ms", counts.middle),
      (s"t > $higherBound ms", counts.high),
      ("failed", counts.ko))
  }

  def responseTimePercentilesOverTime(status: Status, requestName: Option[String], group: Option[Group]): Seq[PercentilesVsTimePlot] =
    resultsHolder.getResponseTimePercentilesBuffers(requestName, group, status).percentiles

  def latencyPercentilesOverTime(status: Status, requestName: Option[String], group: Option[Group]): Seq[PercentilesVsTimePlot] =
    resultsHolder.getLatencyPercentilesBuffers(requestName, group, status).percentiles

  private def timeAgainstGlobalNumberOfRequestsPerSec(buffer: PercentilesBuffers, status: Status, requestName: String, group: Option[Group]): Seq[IntVsTimePlot] = {

    val globalCountsByBucket = resultsHolder.getRequestsPerSecBuffer(None, None, None).counts

    buffer
      .digests
      .map {
        case (time, percentiles) =>
          val count = globalCountsByBucket(time)
          IntVsTimePlot(math.round(count / step * 1000).toInt, percentiles.quantile(0.95).toInt)
      }
      .toSeq
      .sortBy(_.time)
  }

  def responseTimeAgainstGlobalNumberOfRequestsPerSec(status: Status, requestName: String, group: Option[Group]): Seq[IntVsTimePlot] = {
    val percentilesBuffer = resultsHolder.getResponseTimePercentilesBuffers(Some(requestName), group, status)
    timeAgainstGlobalNumberOfRequestsPerSec(percentilesBuffer, status, requestName, group)
  }

  def latencyAgainstGlobalNumberOfRequestsPerSec(status: Status, requestName: String, group: Option[Group]): Seq[IntVsTimePlot] = {
    val percentilesBuffer = resultsHolder.getLatencyPercentilesBuffers(Some(requestName), group, status)
    timeAgainstGlobalNumberOfRequestsPerSec(percentilesBuffer, status, requestName, group)
  }

  def groupCumulatedResponseTimePercentilesOverTime(status: Status, group: Group): Seq[PercentilesVsTimePlot] =
    resultsHolder.getGroupCumulatedResponseTimePercentilesBuffers(group, status).percentiles

  def groupDurationPercentilesOverTime(status: Status, group: Group): Seq[PercentilesVsTimePlot] =
    resultsHolder.getGroupDurationPercentilesBuffers(group, status).percentiles

  def errors(requestName: Option[String], group: Option[Group]): Seq[ErrorStats] = {
    val buff = resultsHolder.getErrorsBuffers(requestName, group)
    val total = buff.foldLeft(0)(_ + _._2)
    buff.toSeq.map { case (name, count) => ErrorStats(name, count, total) }.sortWith(_.count > _.count)
  }
}
