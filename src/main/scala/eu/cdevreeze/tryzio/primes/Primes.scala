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

import scala.annotation.tailrec

import zio.*

/**
 * Finds prime numbers smaller than a given number. Naively implemented.
 *
 * @author
 *   Chris de Vreeze
 */
object Primes:

  opaque type PrimeFactors = List[BigInt]

  object PrimeFactors:
    def apply(factors: List[BigInt]): PrimeFactors = factors

  end PrimeFactors

  extension (factors: PrimeFactors)
    def getFactors: List[BigInt] = factors
    def maxFactor: BigInt = factors.maxOption.getOrElse(BigInt(2))
    def asc: PrimeFactors = factors.reverse
    def show: String = factors.mkString(", ")
    def addFactor(factor: BigInt): PrimeFactors = PrimeFactors(factor :: factors)

  private val zero = BigInt(0)
  private val one = BigInt(1)
  private val two = BigInt(2)
  private val three = BigInt(3)
  private val five = BigInt(5)
  private val six = BigInt(6)
  private val nine = BigInt(9)
  private val ten = BigInt(10)

  private val divBy2Digits: Set[Char] = Set('0', '2', '4', '6', '8')
  private val divBy5Digits: Set[Char] = Set('0', '5')

  /**
   * Finds all prime numbers smaller than or equal to the parameter number.
   */
  def findPrimes(n: BigInt): Task[Seq[BigInt]] =
    val getRoots: Task[List[BigInt]] = ZIO.attempt(two.to(n).toList.filterNot(skipAsRoot))

    if n < 2 then ZIO.succeed(Seq.empty)
    else getRoots.flatMap(roots => discardMultiplesOfRoots(roots))
  end findPrimes

  /**
   * Finds all prime factors of the given number.
   */
  def findPrimeFactors(n: BigInt): Task[PrimeFactors] =
    if n < two then ZIO.fail(new RuntimeException("Expected a number >= 2"))
    else findPrimeFactors(n, two, PrimeFactors(Nil)).map(_.asc)

  private def discardMultiplesOfRoots(numbers: List[BigInt]): Task[List[BigInt]] =
    two.to(numbers.lastOption.getOrElse(one)).foldLeft(ZIO.attempt(numbers)) { (getAccNumbers, nextRoot) =>
      getAccNumbers.flatMap(accNumbers => discardMultiplesOfRoot(accNumbers, nextRoot))
    }
  end discardMultiplesOfRoots

  private def discardMultiplesOfRoot(numbers: List[BigInt], root: BigInt): Task[List[BigInt]] =
    if numbers.isEmpty then ZIO.succeed(numbers)
    else
      val last: BigInt = numbers.last
      if numbers.contains(root) then
        ZIO.attempt {
          val multiples: Set[BigInt] = 1
            .to(numbers.size)
            .scanLeft(root * two) { (acc, _) => acc + root }
            .takeWhile(_ <= last)
            .toSet
          numbers.filterNot(multiples.contains)
        }
      else ZIO.succeed(numbers)
    end if
  end discardMultiplesOfRoot

  @tailrec
  private def findPrimeFactors(n: BigInt, nextPotentialFactor: BigInt, acc: PrimeFactors): Task[PrimeFactors] =
    if n == one then ZIO.succeed(acc)
    else if skipAsRoot(nextPotentialFactor) then findPrimeFactors(n, nextPotentialFactor + 1, acc)
    else if !isDivisibleBy(n, nextPotentialFactor) then findPrimeFactors(n, nextPotentialFactor + 1, acc)
    else
      // The algorithm ensures that nextPotentialFactor is a prime number if we can divide n by it, proven below by reduction ad absurdum.
      // Suppose that nextPotentialFactor is non-prime, and we could divide n by nextPotentialFactor, once or more. Then the prime
      // factors of nextPotentialFactor (once or more) would be smaller than nextPotentialFactor and we would already have divided
      // n by all those prime factors. The GCD (greatest common divisor) of the resulting value of n and nextPotentialFactor
      // would then be 1. So if n is divisible by nextPotentialFactor, nextPotentialFactor must be a prime number.
      // So the prime factors we find are indeed all prime numbers.
      findPrimeFactors(n / nextPotentialFactor, nextPotentialFactor, acc.addFactor(nextPotentialFactor))
  end findPrimeFactors

  private def isDivisibleBy(n: BigInt, m: BigInt): Boolean = n % m == zero

  private def skipAsRoot(n: BigInt): Boolean =
    isMultipleOf2(n) || isMultipleOf5(n) || isMultipleOf9(n) || isMultipleOf3(n)

  private def isMultipleOf2(n: BigInt): Boolean =
    n != two && n.toString.lastOption.exists(c => divBy2Digits.contains(c))

  private def isMultipleOf5(n: BigInt): Boolean =
    n != five && n.toString.lastOption.exists(c => divBy5Digits.contains(c))

  @tailrec
  private def isMultipleOf3(n: BigInt): Boolean =
    if n <= three then false
    else if n < ten then n == six || n == nine
    else
      val digitSum = sumOfDigits(n)
      // Recursion
      isMultipleOf3(digitSum)

  @tailrec
  private def isMultipleOf9(n: BigInt): Boolean =
    if n <= nine then false
    else if n < ten then n == nine
    else
      val digitSum = sumOfDigits(n)
      // Recursion
      isMultipleOf9(digitSum)

  private def sumOfDigits(n: BigInt): BigInt = n.toString.filter(_.isDigit).flatMap(_.toString.toIntOption).sum

end Primes
