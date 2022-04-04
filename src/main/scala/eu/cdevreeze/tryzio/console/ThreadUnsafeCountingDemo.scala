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

import java.util.concurrent.atomic.AtomicInteger

import scala.util.chaining.*

import zio.*
import zio.Console.*

/**
 * This program shows thread unsafety issues of a counter and their mitigation. The thread unsafety issues of a naive counter may be due to
 * lack of locking, or memory visibility issues, or both.
 *
 * For the Ref counter, consider increasing the stack size (-Xss200M).
 *
 * So, if anyone assumes that in a servlet application the HttpServletRequest can be passed to a non-container thread (like a Monix or ZIO
 * managed thread) without any synchronisation, this simple program should take away any unclarity in that regard. Passing the
 * HttpServletRequest with volatile semantics (e.g. by using AtomicReference) should help in that case.
 *
 * @author
 *   Chris de Vreeze
 */
object ThreadUnsafeCountingDemo extends ZIOAppDefault:

  private val n = 1000000

  def run: ZIO[ZEnv, Throwable, Unit] =
    for {
      _ <- printLine("")
      _ <- printLine(s"Counting very unsafe counter $n times (using neither volatile nor locking) ...")
      veryMuchBrokenResult <- incrementVeryMuchBrokenCounterRepeatedly(VeryMuchBrokenCounter(), n)
      _ <- printLine(s"Expected result: $n. Computed (very much broken) result: $veryMuchBrokenResult")
      _ <- printLine("")
      _ <- printLine(s"Counting unsafe counter $n times (volatile, but no locking) ...")
      brokenResult <- incrementBrokenCounterRepeatedly(BrokenCounter(), n)
      _ <- printLine(s"Expected result: $n. Computed (broken) result: $brokenResult")
      _ <- printLine("")
      _ <- printLine(s"Counting subtly safe counter $n times (using AtomicInteger var field) ...")
      subtlySafeResult <- incrementSubtlySafeCounterRepeatedly(SubtlySafeCounter(AtomicInteger(0)), n)
      _ <- printLine(s"Expected result: $n. Computed (subtly safe) result: $subtlySafeResult")
      _ <- printLine("")
      _ <- printLine(s"Counting volatile subtly safe counter $n times (using volatile AtomicInteger var field) ...")
      volatileSubtlySafeResult <- incrementVolatileSubtlySafeCounterRepeatedly(VolatileSubtlySafeCounter(AtomicInteger(0)), n)
      _ <- printLine(s"Expected result: $n. Computed (volatile subtly safe) result: $volatileSubtlySafeResult")
      _ <- printLine("")
      _ <- printLine(s"Counting safe counter $n times (using AtomicInteger val field) ...")
      safeResult <- incrementSafeCounterRepeatedly(SafeCounter(AtomicInteger(0)), n)
      _ <- printLine(s"Expected result: $n. Computed (safe) result: $safeResult")
      _ <- printLine("")
      _ <- printLine(s"Counting AtomicInteger counter $n times ...")
      atomicIntResult <- incrementAtomicIntCounterRepeatedly(AtomicInteger(0), n)
      _ <- printLine(s"Expected result: $n. Computed AtomicInteger result: $atomicIntResult")
      _ <- printLine("")
      _ <- printLine(s"Counting Ref counter $n times ...")
      cnt <- Ref.make(0)
      refResult <- incrementIntRefCounterRepeatedly(cnt, n)
      _ <- printLine(s"Expected result: $n. Computed Ref result: $refResult")
    } yield ()
  end run

  private def incrementVeryMuchBrokenCounterRepeatedly(counter: VeryMuchBrokenCounter, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.currentValue)

  private def incrementBrokenCounterRepeatedly(counter: BrokenCounter, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.currentValue)

  private def incrementSubtlySafeCounterRepeatedly(counter: SubtlySafeCounter, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.counter.get)

  private def incrementVolatileSubtlySafeCounterRepeatedly(counter: VolatileSubtlySafeCounter, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.counter.get)

  private def incrementSafeCounterRepeatedly(counter: SafeCounter, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.counter.get)

  private def incrementAtomicIntCounterRepeatedly(counter: AtomicInteger, n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .map(_ => counter.get)

  private def incrementIntRefCounterRepeatedly(counter: Ref[Int], n: Int): Task[Int] =
    ZIO
      .foreachParDiscard(1.to(n))(_ => incrementTask(counter))
      .flatMap(_ => counter.get)

  private def incrementTask(counter: VeryMuchBrokenCounter): Task[Unit] =
    IO.attempt(counter.increment())

  private def incrementTask(counter: BrokenCounter): Task[Unit] =
    IO.attempt(counter.increment())

  private def incrementTask(counter: SubtlySafeCounter): Task[Unit] =
    IO.attempt(counter.increment())

  private def incrementTask(counter: VolatileSubtlySafeCounter): Task[Unit] =
    IO.attempt(counter.increment())

  private def incrementTask(counter: SafeCounter): Task[Unit] =
    IO.attempt(counter.increment())

  private def incrementTask(counter: AtomicInteger): Task[Unit] =
    IO.attempt(counter.incrementAndGet())

  private def incrementTask(counter: Ref[Int]): Task[Unit] =
    counter.update(_ + 1)

  // Not thread-safe, containing shared mutable state (shared by threads/fibers) without any synchronisation
  // The issue may be lack of locking (more than 1 byte code instruction), or memory visibility, or both
  final class VeryMuchBrokenCounter(var currentValue: Int = 0):
    def increment(): Unit = currentValue += 1

  // Not thread-safe, but trying to at least use volatile semantics
  final class BrokenCounter(@volatile var currentValue: Int = 0):
    def increment(): Unit = currentValue += 1

  // Using var field, but in practice still safe
  final class SubtlySafeCounter(var counter: AtomicInteger):
    def increment(): Unit = counter.incrementAndGet()

  // Using volatile var field, but in practice still safe
  final class VolatileSubtlySafeCounter(@volatile var counter: AtomicInteger):
    def increment(): Unit = counter.incrementAndGet()

  final class SafeCounter(val counter: AtomicInteger):
    def increment(): Unit = counter.incrementAndGet()

end ThreadUnsafeCountingDemo
