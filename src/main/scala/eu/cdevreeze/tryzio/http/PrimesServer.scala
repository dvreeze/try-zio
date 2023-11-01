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

import eu.cdevreeze.tryzio.primes.Primes
import zio.*
import zio.http.*
import zio.metrics.{Metric, MetricLabel}

import java.io.IOException
import scala.util.Try
import scala.util.chaining.*

/**
 * HTTP server exposing prime number queries, using ZIO and zio-http.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimesServer extends ZIOAppDefault:

  private val defaultPort = 8080

  private val portGetter: UIO[Int] = ZIO.config(Config.int("port")).orElseSucceed(defaultPort)

  private def countAllRequests(httpMethod: String, handlerUrl: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counterInt("count_all_requests")
      .fromConst(1)
      .tagged(MetricLabel("httpMethod", httpMethod), MetricLabel("handlerUrl", handlerUrl))

  private def logRequest(url: String): UIO[Unit] =
    ZIO.logDebug(s"Request URL: $url")

  private def logServerStarted(port: Int): UIO[Unit] =
    ZIO.logInfo(s"Server started on port $port")

  val httpApp: HttpApp[Any, Nothing] = Http.collectZIO[Request] {
    case req @ Method.GET -> Path.root / "primes" / number =>
      val getOptNum: Task[Option[BigInt]] = ZIO
        .attempt(BigInt(number))
        .asSome

      getOptNum
        .flatMap { optNum =>
          optNum match
            case None =>
              ZIO.succeed(Response.fromHttpError(HttpError.BadRequest(s"Not an integer: $number")))
            case Some(num) =>
              val getPrimes: Task[Seq[BigInt]] = Primes.findPrimes(num)
              getPrimes
                .mapAttempt(primes => Response.text(s"Primes <= $num: ${primes.mkString(", ")}"))
                .catchAll(_ => ZIO.succeed(Response.fromHttpError(HttpError.InternalServerError(s"No primes found for $number"))))
        }
        .tap(_ => ZIO.attempt(req.url.path.toString).flatMap(path => logRequest(path)))
        .orDie @@ countAllRequests("GET", "/primes")
    case req @ Method.GET -> Path.root / "primeFactors" / number =>
      val getOptNum: Task[Option[BigInt]] = ZIO.attempt(BigInt(number)).asSome

      getOptNum
        .flatMap { optNum =>
          optNum match
            case None =>
              ZIO.succeed(Response.fromHttpError(HttpError.BadRequest(s"Not an integer: $number")))
            case Some(num) =>
              val getPrimeFactors: Task[Primes.PrimeFactors] = Primes.findPrimeFactors(num)
              getPrimeFactors
                .mapAttempt(factors => Response.text(s"Prime factors of $num: ${factors.getFactors.mkString(", ")}"))
                .catchAll(_ => ZIO.succeed(Response.fromHttpError(HttpError.InternalServerError(s"No prime factors found for $number"))))
        }
        .tap(_ => ZIO.attempt(req.url.path.toString).flatMap(path => logRequest(path)))
        .orDie @@ countAllRequests("GET", "/primeFactors")
  }

  def run: UIO[ExitCode] =
    for {
      port <- portGetter
      exitCode <-
        Server
          .serve(httpApp.withDefaultErrorResponse)
          .provide(Server.defaultWithPort(port).tap(_ => logServerStarted(port)))
          .exitCode
    } yield exitCode
  end run

end PrimesServer
