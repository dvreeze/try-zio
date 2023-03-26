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

package eu.cdevreeze.tryzio.zioprops

import zio.*
import zio.test.*
import zio.test.Assertion.*

/**
 * ZIO properties test, checking many functions in its API against their semantics expressed in terms of other functions.
 *
 * @author
 *   Chris de Vreeze
 */
object ZIOPropsTest extends ZIOSpecDefault:

  // ZIOPropsTest is a singleton object, not a class, or else it will not be seen as test.

  private val sampleCount = 500

  private def startEffect(n: BigInt): UIO[BigInt] = ZIO.succeed(n)

  private def startErrorEffect(n: BigInt): IO[BigInt, Nothing] = ZIO.fail(n)

  def spec: Spec[Any, Any] = suite("ZIO properties test") {
    List(
      suite("flatMap, success channel") {
        List(
          test("Function 'map' can be expressed in terms of 'flatMap' (happy flow)") {
            check(genBigInt, genBigIntFunction) { (n, func) =>
              val computed = startEffect(n).map(func)
              val expected = startEffect(n).flatMap(func.andThen(ZIO.succeed(_)))

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function '*>' can be expressed in terms of 'flatMap' (happy flow)") {
            check(genBigInt, genBigInt2) { (n, m) =>
              val effect = ZIO.succeed(m)
              val computed = startEffect(n) *> effect
              val expected = startEffect(n).flatMap(_ => effect)

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'tap' can be expressed in terms of 'flatMap' and 'as' (happy flow)") {
            check(genBigInt, genBigIntFunction) { (n, func) =>
              val effectFunction = func.andThen(ZIO.succeed(_))
              val computed = startEffect(n).tap(effectFunction)
              val expected = startEffect(n).flatMap(k => effectFunction(k).as(k))

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'as' can be expressed in terms of 'map' (happy flow)") {
            check(genBigInt) { n =>
              val m = n + 123
              val computed = startEffect(n).as(m)
              val expected = startEffect(n).map(_ => m)

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'asRight' can be expressed in terms of 'map' (happy flow)") {
            check(genBigInt) { n =>
              val computed = startEffect(n).asRight
              val expected = startEffect(n).map(Right(_))

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'asSome' can be expressed in terms of 'map' (happy flow)") {
            check(genBigInt) { n =>
              val computed = startEffect(n).asSome
              val expected = startEffect(n).map(Option(_))

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'ZIO.when' can be expressed in terms of 'map' (happy flow)") {
            check(Gen.boolean, genBigInt) { (b, n) =>
              val computed = ZIO.when(b)(startEffect(n))
              val expected = startEffect(n).map { i => if b then Some(i) else None }

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount),
          test("Function 'ZIO.whenZIO' can be expressed in terms of 'flatMap' and 'map' (happy flow)") {
            check(Gen.boolean, genBigInt) { (b, n) =>
              val predEffect = ZIO.succeed(b)
              val computed = ZIO.whenZIO(predEffect)(startEffect(n))
              val expected =
                predEffect.flatMap { pred =>
                  startEffect(n).map { k =>
                    if pred then Some(k) else None
                  }
                }

              for {
                x <- computed
                y <- expected
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount)
        )
      },
      suite("flatMapError, error channel") {
        List(
          test("Function 'mapError' can be expressed in terms of 'flatMapError' (error flow)") {
            check(genBigInt, genBigIntFunction) { (n, func) =>
              val computed = startErrorEffect(n).mapError(func)
              val expected = startErrorEffect(n).flatMapError(func.andThen(ZIO.succeed(_)))

              for {
                x <- computed.flip
                y <- expected.flip
              } yield assertTrue(x == y)
            }
          } @@ TestAspect.samples(sampleCount)
        )
      }
    )
  }

  private val genBigInt: Gen[Any, BigInt] = Gen.bigInt(1, 15000)

  private val genBigInt2: Gen[Any, BigInt] = Gen.bigInt(1, 30000)

  2 // Total functions that do not fail ("DTP": deterministic, total, pure)
  private val genBigIntFunction: Gen[Any, BigInt => BigInt] =
    Gen.fromIterable(
      List(
        _ + 1,
        _ - 1,
        _ * 2,
        _ / 2,
        _ % 2,
        n => n * n,
        n => n + 2 * n
      )
    )

end ZIOPropsTest
