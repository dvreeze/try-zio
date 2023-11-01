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

import eu.cdevreeze.tryzio.primes.Primes
import jakarta.servlet.{AsyncContext, ServletException}
import jakarta.servlet.annotation.WebServlet
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import zio.*

import java.io.{IOException, PrintWriter}
import java.net.URI
import java.nio.file.{FileSystems, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration as SDuration, *}
import scala.util.chaining.*

/**
 * Servlet supporting prime factor queries, using the unlikely combination of ZIO and (async) servlets (on Tomcat/Jetty).
 *
 * Note the contrast between FP when using ZIO on the one hand and the opposite of FP when using the servlet API on the other hand. The idea
 * here is to show that integration is possible between those 2 worlds (even if sub-optimal), and that we keep control over blocking and
 * (partly) single-threaded request handling, if need be. Note that some legacy Java APIs may require blocking on one thread, due to the use
 * of thread locals under the hood.
 *
 * Below, for each HTTP request there are multiple threads involved: the initial request handling thread (from the container), the "async"
 * thread (also from the container), and threads running the ZIO effects (and response writing). Also note that multiple "programming
 * models" are involved: normal side-effecting code, ZIO effects that by themselves run nothing but are composable functional effects, and
 * Scala Future objects that represent "wannabe values" (in the sense that creating them starts to run them, even when mapping/flatMapping
 * over created Futures). This means 3 different "mental models" to reason about code. Also note that a lot is done to make sure objects are
 * safely published across threads.
 *
 * @author
 *   Chris de Vreeze
 */
@WebServlet(name = "primeFactorServlet", urlPatterns = Array("/primeFactors/*"), asyncSupported = true)
class PrimeFactorServlet extends HttpServlet:

  @throws[ServletException]
  @throws[IOException]
  protected override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit =
    // Not very robust in error handling
    val requestUri = URI.create(request.getRequestURI)
    val path: Path = FileSystems.getDefault
      .getPath(requestUri.getPath.stripPrefix("/"))
      .pipe(p => p.ensuring(p.getNameCount == 2, s"Expected path like '/primeFactors/144' but got path '/$p'"))
    val numberString: String = path.subpath(path.getNameCount - 1, path.getNameCount).toString

    showThread(path.toString, "first request handling thread")
    val asyncContext: AsyncContext = request.startAsync(request, response)

    asyncContext.start(() => handleGetAsync(asyncContext, path, numberString))
  end doGet

  private def handleGetAsync(asyncContext: AsyncContext, path: Path, numberString: String): Unit =
    // Asynchronous run of the ZIO effect, at the edge of request handling
    // The code in this function starts on the thread dedicated to the AsyncContext, but mostly runs in
    // another thread, as cancelable Future. Hence (safer) ZIO timeouts can be used. That in itself makes
    // a strong case for an effect system like ZIO, even if combined with servlets it is a hassle.

    showThread(path.toString, "Async request handling thread")

    // Safe way to get response object in async context, avoiding memory visibility issues (see Java memory model)
    // See for example https://docs.oracle.com/javaee/7/tutorial/servlets012.htm.
    val res: HttpServletResponse = asyncContext.getResponse.asInstanceOf[HttpServletResponse]

    // We use more threads, however, so let's use AtomicReference for its memory visibility guarantees.
    val asyncContextRef: AtomicReference[AsyncContext] = new AtomicReference(asyncContext)
    val resRef: AtomicReference[HttpServletResponse] = new AtomicReference(res)
    val resWriterRef: AtomicReference[PrintWriter] = new AtomicReference(res.getWriter)

    // Yet another "async" boundary (and therefore thread switch).
    // TODO How to run parts of the resulting ZIO effect in a blocking way (without too many memory barriers and lack of cancellability)?
    val responseTextOptionFuture: CancelableFuture[Either[Throwable, Option[String]]] =
      Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe.runToFuture {
          getResponseTextTask(numberString.stripPrefix("/"), path.toString)
            .timeoutFail(new RuntimeException("Timeout"))(60.seconds) // Must be smaller than request timeout, if any
            .either
        }
      }

    // Another potential thread switch, using the safely published atomic reference to the HttpServletResponse.
    // Note that Future.map results in a Future that also runs as soon as possible. No need to do a "blocking wait" either.
    responseTextOptionFuture.map { responseTextOption =>
      writeResponse(responseTextOption, numberString, resRef, resWriterRef)
      showThread(path.toString, "'response writing' thread")
      asyncContextRef.get.complete()
    }
  end handleGetAsync

  // ZIO functional effect
  private def getResponseTextTask(numberAsString: String, servletPath: String): Task[Option[String]] =
    val getOptNum: Task[Option[BigInt]] = ZIO.attempt(BigInt(numberAsString)).asSome

    getOptNum
      .flatMap { optNum =>
        optNum match
          case None => ZIO.succeed(None)
          case Some(num) =>
            val getPrimeFactors: Task[Primes.PrimeFactors] = Primes.findPrimeFactors(num)
            getPrimeFactors
              .map(factors => Some(s"Prime factors of $num: ${factors.getFactors.mkString(", ")}"))
              .catchAll(_ => ZIO.fail(new ServletException(s"No prime factors found for $numberAsString")))
      }
      .tap(_ => ZIO.attempt(servletPath).flatMap(path => showThreadTask(path, "a ZIO 'do real work' thread")))
  end getResponseTextTask

  // ZIO functional effect
  private def showThreadTask(url: String, source: String): Task[Unit] =
    ZIO.attempt(showThread(url, source))

  private def showThread(url: String, source: String): Unit =
    println(s"URL: $url. Current thread (from $source): ${Thread.currentThread()}")

  private def writeResponse(
      responseTextOption: Either[Throwable, Option[String]],
      numberString: String,
      responseRef: AtomicReference[HttpServletResponse],
      responseWriterRef: AtomicReference[PrintWriter]
  ): Unit =
    val res: HttpServletResponse = responseRef.get
    res.setContentType("text/plain")
    // Does the "volatile semantics" also guarantee that the response Writer has been safely published? I am not sure.
    // Hence the atomic reference to the PrintWriter as well.
    val writer: PrintWriter = responseWriterRef.get
    responseTextOption match
      case Left(t) =>
        res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, s"No prime factors found for $numberString")
      case Right(None) =>
        res.sendError(HttpServletResponse.SC_BAD_REQUEST, s"Not an integer: $numberString")
      case Right(Some(text)) =>
        writer.println(text)
        writer.flush()
  end writeResponse

end PrimeFactorServlet
