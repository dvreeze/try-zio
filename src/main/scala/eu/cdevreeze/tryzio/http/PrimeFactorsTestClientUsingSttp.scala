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

package eu.cdevreeze.tryzio.http

import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.httpclient.zio.SttpClient
import sttp.client4.httpclient.zio.send
import sttp.model.Uri
import zio.*

/**
 * HTTP client program simultaneously querying the server for prime factors of multiple numbers, using ZIO and sttp.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimeFactorsTestClientUsingSttp extends ZIOAppDefault:

  private case class AppConfig(host: String, port: Int, minNumber: BigInt, maxNumber: BigInt)

  private val defaultHost = "localhost"
  private val defaultPort = 8080
  private val defaultMinNumber = BigInt(12_000_000)
  private val defaultMaxNumber = defaultMinNumber + 200

  private val hostGetter: UIO[String] = ZIO.config(Config.string("host")).orElseSucceed(defaultHost)
  private val portGetter: UIO[Int] = ZIO.config(Config.int("port")).orElseSucceed(defaultPort)

  def run: URIO[ZIOAppArgs, ExitCode] =
    val argsGetter: ZIO[ZIOAppArgs, Throwable, Chunk[String]] = getArgs
    val configGetter: URIO[ZIOAppArgs, AppConfig] =
      (for {
        host <- hostGetter
        port <- portGetter
        args <- argsGetter
        minNumber <- ZIO
          .attempt(args.head)
          .mapAttempt(n => BigInt(n))
          .orElseSucceed(defaultMinNumber)
        maxNumber <- ZIO
          .attempt(args.drop(1).head)
          .mapAttempt(n => BigInt(n))
          .orElseSucceed(defaultMaxNumber)
      } yield AppConfig(host, port, minNumber, maxNumber)).orDie

    val getStringResponses: RIO[ZIOAppArgs, Seq[String]] =
      for {
        cfg <- configGetter
        numbers <- ZIO.attempt(cfg.minNumber.to(cfg.maxNumber))
        numberOfProcessors <- ZIO
          .attempt(java.lang.Runtime.getRuntime.availableProcessors)
          .tap(n => ZIO.logInfo(s"Number of available processors: $n"))
        responseStringsFiber <- ZIO
          .foreachPar(numbers.toList) { number =>
            getUrl(cfg.host, cfg.port, number)
              .flatMap(getResponseAsString)
              .tap(s => ZIO.logInfo(s))
              .provideLayer(HttpClientZioBackend.layer())
          }
          .withParallelism(1.max(numberOfProcessors / 2))
          .fork
        responseStrings <- responseStringsFiber.join
      } yield responseStrings

    getStringResponses.tapError(t => ZIO.logError(t.getMessage)).orDie.exitCode
  end run

  private def getResponseAsString(url: Uri): RIO[SttpClient, String] =
    for {
      request <- getRequest(url)
      response <- send(request).tapError(t => ZIO.logError(s"Error sending request: ${t.getMessage}"))
    } yield response.body

  private def getRequest(url: Uri): Task[Request[String]] =
    ZIO.attempt { basicRequest.get(url).response(asStringAlways) }

  private def getUrl(host: String, port: Int, number: BigInt): Task[Uri] =
    ZIO.attempt { uri"http://$host:$port/primeFactors/$number" }

end PrimeFactorsTestClientUsingSttp
