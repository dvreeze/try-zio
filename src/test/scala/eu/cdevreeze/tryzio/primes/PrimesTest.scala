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

package eu.cdevreeze.tryzio.primes

import eu.cdevreeze.tryzio.primes.Primes
import eu.cdevreeze.tryzio.primes.Primes.PrimeFactors
import zio.Random
import zio.Task
import zio.UIO
import zio.ZIO
import zio.stream.ZStream
import zio.test.Assertion.*
import zio.test.Gen
import zio.test.Sample
import zio.test.ZIOSpecDefault
import zio.test.assert
import zio.test.check
import zio.test.magnolia.*
import zio.test.suite

/**
 * Primes test.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimesTest extends ZIOSpecDefault:

  // PrimesTest is a singleton object, not a class, or else it will not be seen as test.

  def spec = suite("Primes test") {
    List(
      test("A positive integer >= 2 is equal to the multiplication results of its prime factors") {
        check(Gen.bigInt(2, BigInt(120000))) { number =>
          val getPrimeFactors: Task[PrimeFactors] = Primes.findPrimeFactors(number)

          for {
            primeFactors <- getPrimeFactors
            multiplyResult <- multiply(primeFactors.getFactors)
          } yield assert(multiplyResult)(equalTo(number))
        }
      },
      test("A prime number has only one prime factor, equal to itself") {
        check(genPrime) { prime =>
          for {
            primeFactors <- Primes.findPrimeFactors(prime)
          } yield assert(primeFactors)(equalTo(List(prime)))
        }
      },
      test("A non-prime number > 2 has at least two prime factors") {
        check(genNonPrime) { nonPrime =>
          for {
            primeFactors <- Primes.findPrimeFactors(nonPrime)
          } yield assert(primeFactors.getFactors.size)(isGreaterThanEqualTo(2))
        }
      },
      test("A prime number is a probable prime, according to the standard library") {
        check(genPrime) { prime =>
          assert(prime.isProbablePrime(100))(isTrue)
        }
      },
      test("A non-prime number is not a probable prime, according to the standard library") {
        check(genNonPrime) { nonPrime =>
          assert(nonPrime.isProbablePrime(100))(isFalse)
        }
      }
    )
  }

  private val maxValue = BigInt(1200)

  private val genPrime: Gen[Any, BigInt] =
    val primeStream: ZStream[Any, Nothing, BigInt] = ZStream.fromIterableZIO(Primes.findPrimes(maxValue).orDie)
    Gen(primeStream.map(n => Some(Sample.noShrink(n))))

  private val genNonPrime: Gen[Any, BigInt] =
    val numbers = BigInt(2).to(maxValue)
    val getNonPrimes: UIO[Seq[BigInt]] =
      Primes.findPrimes(maxValue).map(_.toSet).map(primes => numbers.filterNot(primes)).orDie
    val nonPrimeStream: ZStream[Any, Nothing, BigInt] = ZStream.fromIterableZIO(getNonPrimes)
    Gen(nonPrimeStream.map(n => Some(Sample.noShrink(n))))

  private def multiply(numbers: List[BigInt]): Task[BigInt] =
    if numbers.isEmpty then ZIO.fail(sys.error("Expected at least one number to multiply"))
    else if numbers.sizeIs == 1 then ZIO.succeed(numbers.head)
    else ZIO.attempt(numbers.head).flatMap(n => multiply(numbers.tail).map(_ * n))

end PrimesTest
