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

package eu.cdevreeze.tryzio.http.legacy

import java.io.IOException
import java.io.PrintWriter
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

import eu.cdevreeze.tryzio.primes.Primes
import jakarta.servlet.AsyncContext
import jakarta.servlet.ServletException
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import zio.*

/**
 * Servlet supporting prime factor queries, using the unlikely combination of ZIO and (async) servlets (on Jetty).
 *
 * Note the contrast between FP when using ZIO on the one hand and the opposite of FP when using the servlet API on the other hand. The idea
 * here is to show that integration is possible between those 2 worlds (even if sub-optimal), and that we keep control over locking of
 * threads, if need be. Note that some legacy Java APIs may require blocking on one thread.
 *
 * @author
 *   Chris de Vreeze
 */
@WebServlet(name = "primeFactorServlet", urlPatterns = Array("/primeFactors/*"), asyncSupported = true)
class PrimeFactorServlet extends HttpServlet:

  private val defaultPort = 8080

  @throws[ServletException]
  @throws[IOException]
  protected override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit =
    val asyncContext: AsyncContext = request.startAsync()

    // Not very robust in error handling
    val requestUri = URI.create(request.getRequestURI)
    val path: Path = FileSystems.getDefault.getPath(requestUri.getPath.stripPrefix("/"))
    val numberString = path.subpath(path.getNameCount - 1, path.getNameCount)

    asyncContext.start { () =>
      // Synchronous run of the ZIO effect, at the edge of request handling
      try {
        val responseTextOption: Either[Throwable, Option[String]] = Runtime.default.unsafeRun {
          getResponseTextTask(numberString.toString.stripPrefix("/"), path.toString).either
        }
        response.setContentType("text/plain")
        val writer: PrintWriter = response.getWriter
        responseTextOption match
          case Left(t) =>
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, s"No prime factors found for $numberString")
          case Right(None) =>
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, s"Not an integer: $numberString")
          case Right(Some(text)) =>
            writer.println(text)
            writer.flush()
      } finally {
        asyncContext.complete()
      }
    }
  end doGet

  // ZIO functional effect
  private def getResponseTextTask(numberAsString: String, servletPath: String): Task[Option[String]] =
    val getOptNum: Task[Option[BigInt]] = Task.attempt(BigInt(numberAsString)).asSome

    getOptNum
      .flatMap { optNum =>
        optNum match
          case None => IO.succeed(None)
          case Some(num) =>
            val getPrimeFactors: Task[Primes.PrimeFactors] = Primes.findPrimeFactors(num)
            getPrimeFactors
              .map(factors => Some(s"Prime factors of $num: ${factors.getFactors.mkString(", ")}"))
              .catchAll(_ => IO.fail(new ServletException(s"No prime factors found for $numberAsString")))
      }
      .tap(_ => IO.attempt(servletPath).flatMap(path => showThreadTask(path)))
  end getResponseTextTask

  // ZIO functional effect
  private def showThreadTask(url: String): Task[Unit] =
    IO.attempt(println(s"Current thread: ${Thread.currentThread()}. URL: $url"))

end PrimeFactorServlet
