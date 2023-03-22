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

package eu.cdevreeze.tryzio.console

import eu.cdevreeze.tryzio.primes.Primes
import zio.*
import zio.Console.*

/**
 * Prime numbers finder, using ZIO.
 *
 * @author
 *   Chris de Vreeze
 */
object FindPrimes extends ZIOAppDefault:

  def run: Task[Unit] =
    for {
      _ <- printLine("Enter an integer number:")
      num <- readLine.flatMap(n => ZIO.attempt(BigInt(n)).tapError(_ => printLine(s"Not an integer number: $n")))
      _ <- printLine(s"The prime numbers smaller than or equal to $num are:")
      primesFiber <- Primes.findPrimes(num).fork
      primes <- primesFiber.join
      sortedPrimes <- ZIO.attempt(primes.sorted)
      _ <- ZIO.foreach(sortedPrimes)(p => printLine(p))
    } yield ()

end FindPrimes
