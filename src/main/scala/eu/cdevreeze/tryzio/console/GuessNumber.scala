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

import zio.*
import zio.Console.*

/**
 * Number guessing, using ZIO.
 *
 * @author
 *   Chris de Vreeze
 */
object GuessNumber extends ZIOAppDefault:

  def run: Task[Boolean] =
    val getInitialAttemptNumber: UIO[Ref[Int]] = Ref.make(0)
    def getNextAttemptNumber(ref: Ref[Int]): UIO[Ref[Int]] = ref.update(_ + 1) *> IO.succeed(ref)

    for {
      ref <- getInitialAttemptNumber
      b <- getNextAttemptNumber(ref).flatMap(_.get).flatMap(guessOnce).repeatUntilEquals(true)
    } yield b
  end run

  private def guessOnce(attempt: Int): Task[Boolean] =
    val getRandomNumber: UIO[Int] = Random.nextIntBetween(0, 10 + 1)
    for {
      num <- getRandomNumber
      _ <- printLine("")
      _ <- printLine(s"Attempt $attempt. Guess a number between 0 and 10 (inclusive):")
      guessedNumStr <- readLine
      guessedIt <- IO.succeed(guessedNumStr.toIntOption.contains(num))
      _ <- printLine(if guessedIt then "You guessed the number" else s"You did not guess the number ($num)")
    } yield guessedIt
  end guessOnce

end GuessNumber
