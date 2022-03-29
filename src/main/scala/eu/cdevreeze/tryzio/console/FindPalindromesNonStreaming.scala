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

import java.io.File

import scala.io.Codec
import scala.io.Source

import zio.*
import zio.Console.*

/**
 * Finds palindromes in a file of words, using ZIO (without streams). The program expects the input file path as program argument.
 *
 * @author
 *   Chris de Vreeze
 */
object FindPalindromesNonStreaming extends ZIOAppDefault:

  def run: ZIO[ZEnv & ZIOAppArgs, Throwable, Seq[String]] =
    val argsGetter: ZIO[ZEnv & ZIOAppArgs, Throwable, Chunk[String]] = FindPalindromesNonStreaming.validateEnv(getArgs)
    for {
      args <- argsGetter
      path <- ZIO.attempt(args.head).tapError(_ => printLine("Missing arg (input file)"))
      result <- run(new File(path))
      _ <- printLine("Found palindromes:")
      _ <- ZIO.foreach(result)(res => printLine(res)).unit
    } yield result
  end run

  def run(f: File): Task[Seq[String]] =
    IO.attempt(Source.fromFile(f)(Codec.UTF8)).acquireReleaseWith(src => IO.succeed(src.close())) { src =>
      val getWords: Task[Seq[String]] =
        ZIO.attempt(src.getLines.toSeq).flatMap(lines => ZIO.collectAll(lines.map(IO.succeed)))
      val getPalindromes: Task[Seq[String]] =
        for {
          words <- getWords
          palindromes <- filterPalindromes(words)
          sortedPalindromes <- IO.attempt(palindromes.sortBy(word => (-word.length, word)))
        } yield sortedPalindromes
      getPalindromes
    }
  end run

  private def filterPalindromes(words: Seq[String]): Task[Seq[String]] =
    ZIO.filter(words)(isPalindrome).map(_.filter(_.lengthIs >= 2))

  private def isPalindrome(s: String): Task[Boolean] = Task.attempt(s.equalsIgnoreCase(s.reverse))

end FindPalindromesNonStreaming
