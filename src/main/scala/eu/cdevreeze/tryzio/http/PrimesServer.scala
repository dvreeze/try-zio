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

import java.io.IOException

import scala.util.Try
import scala.util.chaining.*

import eu.cdevreeze.tryzio.http.PrimesServer.validateEnv
import eu.cdevreeze.tryzio.primes.Primes
import zhttp.http.*
import zhttp.service.*
import zhttp.service.Server.Start
import zhttp.service.server.ServerChannelFactory
import zio.*
import zio.Console.printLine

/**
 * HTTP server exposing prime number queries, using ZIO and zio-http.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimesServer extends ZIOAppDefault:

  private val defaultPort = 8080

  private def showThread(url: String): Task[Unit] =
    printLine(s"Current thread: ${Thread.currentThread()}. URL: $url")

  val httpApp: HttpApp[Any, Nothing] = Http.collectZIO[Request] {
    case req @ Method.GET -> !! / "primes" / number =>
      val getOptNum: Task[Option[BigInt]] = ZIO.attempt(BigInt(number)).asSome

      getOptNum
        .flatMap { optNum =>
          optNum match
            case None =>
              ZIO.succeed(Response.fromHttpError(HttpError.BadRequest(s"Not an integer: $number")))
            case Some(num) =>
              val getPrimes: Task[Seq[BigInt]] = Primes.findPrimes(num)
              getPrimes
                .map(primes => Response.text(s"Primes <= $num: ${primes.mkString(", ")}"))
                .catchAll(_ => ZIO.succeed(Response.fromHttpError(HttpError.InternalServerError(s"No primes found for $number"))))
        }
        .tap(_ => ZIO.attempt(req.url.path.toString).flatMap(path => showThread(path)))
        .orDie
    case req @ Method.GET -> !! / "primeFactors" / number =>
      val getOptNum: Task[Option[BigInt]] = ZIO.attempt(BigInt(number)).asSome

      getOptNum
        .flatMap { optNum =>
          optNum match
            case None =>
              ZIO.succeed(Response.fromHttpError(HttpError.BadRequest(s"Not an integer: $number")))
            case Some(num) =>
              val getPrimeFactors: Task[Primes.PrimeFactors] = Primes.findPrimeFactors(num)
              getPrimeFactors
                .map(factors => Response.text(s"Prime factors of $num: ${factors.getFactors.mkString(", ")}"))
                .catchAll(_ => ZIO.succeed(Response.fromHttpError(HttpError.InternalServerError(s"No prime factors found for $number"))))
        }
        .tap(_ => ZIO.attempt(req.url.path.toString).flatMap(path => showThread(path)))
        .orDie
  }

  def run: URIO[ZIOAppArgs, ExitCode] =
    val argsGetter: URIO[ZIOAppArgs, Chunk[String]] = getArgs
    val portGetter: URIO[ZIOAppArgs, Int] = argsGetter
      .flatMap { args =>
        ZIO.attempt(args.headOption.getOrElse(defaultPort.toString).toInt)
      }
      .catchAll(_ => ZIO.succeed(defaultPort))

    def printServerStarted(port: Int): ZIO[Any, IOException, Unit] = printLine(s"Server started on port $port")

    val threadCount: Int = Try(java.lang.Runtime.getRuntime.availableProcessors()).getOrElse(2)

    portGetter.flatMap { port =>
      Server(httpApp)
        .withPort(port)
        .make
        .pipe { startZIO =>
          ZIO.scoped[EventLoopGroup & ServerChannelFactory]((startZIO <* printServerStarted(port)) *> ZIO.never)
        }
        .provideSomeLayer(ServerChannelFactory.auto ++ EventLoopGroup.auto(threadCount))
        .exitCode
    }
  end run

end PrimesServer
