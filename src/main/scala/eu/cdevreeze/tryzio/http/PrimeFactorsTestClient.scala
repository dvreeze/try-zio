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

import java.net.URI

import scala.util.Try
import scala.util.chaining.*

import zio.*
import zio.Console.*
import zio.http.*
import zio.http.model.*

/**
 * HTTP client program simultaneously querying the server for prime factors of multiple numbers, using ZIO and zio-http.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimeFactorsTestClient extends ZIOAppDefault:

  private case class Config(host: String, port: Int, minNumber: BigInt, maxNumber: BigInt)

  private val defaultHost = "localhost"
  private val defaultPort = 8080
  private val defaultMinNumber = BigInt(12_000_000)
  private val defaultMaxNumber = defaultMinNumber + 200

  def run: URIO[ZIOAppArgs, ExitCode] =
    val argsGetter: ZIO[ZIOAppArgs, Throwable, Chunk[String]] = getArgs
    val configGetter: URIO[ZIOAppArgs, Config] = argsGetter.flatMap { args =>
      ZIO.attempt {
        val host = args.headOption.getOrElse(defaultHost)
        val port = args.drop(1).headOption.getOrElse(defaultPort.toString).toIntOption.getOrElse(defaultPort)
        val minNumber = args.drop(2).headOption.getOrElse(defaultMinNumber.toString).pipe(n => Try(BigInt(n)).getOrElse(defaultMinNumber))
        val maxNumber = args.drop(3).headOption.getOrElse(defaultMaxNumber.toString).pipe(n => Try(BigInt(n)).getOrElse(defaultMaxNumber))
        Config(host, port, minNumber, maxNumber)
      }
    }.orDie

    val getStringResponses: RIO[ZIOAppArgs, Seq[String]] =
      for {
        cfg <- configGetter
        numbers <- ZIO.attempt(cfg.minNumber.to(cfg.maxNumber))
        numberOfProcessors <- ZIO
          .attempt(java.lang.Runtime.getRuntime.availableProcessors)
          .tap(n => printLine(s"Number of available processors: $n"))
        responseStringsFiber <- ZIO
          .foreachPar(numbers.toList) { number =>
            getUrl(cfg.host, cfg.port, number)
              .flatMap(getResponseAsString)
              .tap(printLine(_))
              .provide(Client.default)
          }
          .withParallelism(1.max(numberOfProcessors / 2))
          .fork
        responseStrings <- responseStringsFiber.join
      } yield responseStrings

    getStringResponses.tapError(t => printError(t)).orDie.exitCode
  end run

  private def getResponseAsString(url: URI): RIO[Client, String] =
    for {
      headers <- ZIO.attempt(Headers.host(url.getHost))
      response <- Client.request(url = url.toString, headers = headers)
      content <- response.body.asString
    } yield content

  private def getUrl(host: String, port: Int, number: BigInt): Task[URI] =
    ZIO.attempt { URI.create(s"http://$host:$port/primeFactors/$number") }

end PrimeFactorsTestClient
