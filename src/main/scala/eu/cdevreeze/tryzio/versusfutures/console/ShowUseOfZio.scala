/*
 * Copyright 2022-2022 Chris de Vreeze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cdevreeze.tryzio.versusfutures.console

import zio.*
import zio.Console.*

import java.net.URI

/**
 * Shows use of ZIO replacing the use of Scala Futures. We can create ZIO functional effects when and where we want, and combine them
 * without worrying about any side-effects like those of started and therefore already running Scala Futures.
 *
 * @author
 *   Chris de Vreeze
 */
object ShowUseOfZio extends ZIOAppDefault:

  final case class SiteCount(uri: URI, count: Int)

  private val numberOfSites = 5

  private val siteCounts: Map[Int, SiteCount] =
    0.until(numberOfSites).map(i => i -> SiteCount(URI.create(s"http://www.test$i.com/counter"), 10000 + i)).toMap

  def run: Task[Unit] =
    getTotalCount().flatMap(cnt => printLine(s"Total count: $cnt").delay(2000L.milliseconds))

  def getTotalCount(): Task[Int] =
    // Sometimes the program will fail but method failFetchCount will not be called
    def getUnavailableSiteIndexOption: Task[Option[Int]] =
      Random
        .nextIntBounded(numberOfSites * 3)
        .tap(i => printLine(s"Random site number $i chosen"))
        .map(i => Option(i).filter(_ < numberOfSites))

    // Each ZIO functional effect is just an immutable value, and not code that starts running by itself.
    // Therefore we can create them when and where we want, without causing any side-effects. It's the ZIO
    // runtime that will run the resulting Task that is returned by the "run" method.
    // As we can see below, we still have control over parallelism in the sense that we can call methods like "ZIO.collectAllPar".
    // And very important in this example: if checkCanFetch returns an error, method failFetchCount will never be called.
    def checkCanFetchTask(using unavailableSiteIndexOption: Option[Int]): Task[Unit] = checkCanFetch
    def fetchCountTasks(using unavailableSiteIndexOption: Option[Int]): Seq[Task[Int]] =
      0.until(numberOfSites).map(i => fetchCount(i))

    for
      given Option[Int] <- getUnavailableSiteIndexOption
      _ <- checkCanFetchTask
      counts <- ZIO.collectAllPar(fetchCountTasks)
    yield counts.sum
  end getTotalCount

  private def checkCanFetch(using unavailableSiteIndexOption: Option[Int]): Task[Unit] =
    printLine("Start: checkCanFetch()") *>
      ZIO.sleep(250L.milliseconds) *>
      printLine(s"Optional unavailable site index: $unavailableSiteIndexOption") *>
      unavailableSiteIndexOption
        .map(i => ZIO.fail(new RuntimeException(s"Site $i is unavailable")))
        .getOrElse(ZIO.unit)

  private def fetchCount(siteIndex: Int)(using unavailableSiteIndexOption: Option[Int]): Task[Int] =
    if unavailableSiteIndexOption.contains(siteIndex) then failFetchCount(siteIndex)
    else doFetchCount(siteIndex)

  private def doFetchCount(siteIndex: Int): Task[Int] =
    printLine(s"Start: doFetchCount($siteIndex)") *>
      ZIO.sleep(800L.milliseconds) *>
      printLine(s"End: doFetchCount($siteIndex)") *>
      ZIO.attempt { siteCounts.get(siteIndex).map(_.count).getOrElse(0) }

  // This method will never be called (which is intended here), because "method checkCanFetch prevents that"
  private def failFetchCount(siteIndex: Int): Task[Int] =
    printLine(s"Start: failFetchCount($siteIndex). THIS METHOD SHOULD NOT BE CALLED!") *>
      ZIO.sleep(2300L.milliseconds) *>
      printLine(s"End: failFetchCount($siteIndex)") *>
      ZIO.fail(new RuntimeException(s"Site $siteIndex unavailable, so could not get total count. THIS EXCEPTION SHOULD NOT BE THROWN!"))

end ShowUseOfZio
