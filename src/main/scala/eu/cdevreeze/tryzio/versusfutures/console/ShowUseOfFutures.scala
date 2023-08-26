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

import java.net.URI

import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.Random
import scala.util.chaining.scalaUtilChainingOps

/**
 * Shows bug fix for buggy use of Scala Futures. It's still not a pretty program that can easily be reasoned about.
 *
 * @author
 *   Chris de Vreeze
 */
object ShowUseOfFutures:

  final case class SiteCount(uri: URI, count: Int)

  private val numberOfSites = 5

  private val siteCounts: Map[Int, SiteCount] =
    0.until(numberOfSites).map(i => i -> SiteCount(URI.create(s"http://www.test$i.com/counter"), 10000 + i)).toMap

  def main(args: Array[String]): Unit =
    Await.result(
      getTotalCount().map { cnt =>
        Thread.sleep(2000L)
        println(s"Total count: $cnt")
      },
      Duration.Inf
    )

  def getTotalCount(): Future[Int] =
    // Sometimes the program will fail but no longer calling method failFetchCount, which indeed shouldn't be called
    val random = new Random()
    given unavailableSiteIndexOption: Option[Int] = random.nextInt(numberOfSites * 3).pipe(i => Option(i).filter(_ < numberOfSites))

    // Creating Futures that run in parallel, but the first one ends before deciding to start the other ones.
    // Each created Future (but the first one) may start running immediately, but is created after the first one finishes.
    val checkCanFetchFuture: Future[Unit] = checkCanFetch
    // The only change compared to the buggy version in ShowbuggyUseOfFutures: not creating these Futures immediately.
    def fetchCountFutures(): Seq[Future[Int]] = 0.until(numberOfSites).map(i => fetchCount(i))

    for {
      _ <- checkCanFetchFuture
      counts <- Future.sequence(fetchCountFutures())
    } yield counts.sum

  private def checkCanFetch(using unavailableSiteIndexOption: Option[Int]): Future[Unit] =
    Future {
      println("Start: checkCanFetch()")
      Thread.sleep(250L)
      println(s"Optional unavailable site index: $unavailableSiteIndexOption")
      unavailableSiteIndexOption.foreach(i => throw new RuntimeException(s"Site $i is unavailable"))
    }

  private def fetchCount(siteIndex: Int)(using unavailableSiteIndexOption: Option[Int]): Future[Int] =
    if unavailableSiteIndexOption.contains(siteIndex) then failFetchCount(siteIndex)
    else doFetchCount(siteIndex)

  private def doFetchCount(siteIndex: Int): Future[Int] =
    Future {
      println(s"Start: doFetchCount($siteIndex)")
      Thread.sleep(800L)
      println(s"End: doFetchCount($siteIndex)")
      siteCounts.get(siteIndex).map(_.count).getOrElse(0)
    }

  // Will not be called! Before that method checkCanFetch has already thrown an exception.
  private def failFetchCount(siteIndex: Int): Future[Int] =
    Future {
      println(s"Start: failFetchCount($siteIndex). THIS METHOD SHOULD NOT BE CALLED!")
      Thread.sleep(2300L)
      println(s"End: failFetchCount($siteIndex)")
      throw new RuntimeException(s"Site $siteIndex unavailable, so could not get total count. THIS EXCEPTION SHOULD NOT BE THROWN!")
    }

end ShowUseOfFutures
