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
import zio.stream.*

/**
 * Finds palindromes in a file of words, using ZIO (streams). The program expects the input file path as program argument.
 *
 * @author
 *   Chris de Vreeze
 */
object FindPalindromes extends ZIOAppDefault:

  // Below, "running" the stream is safe, returning a ZIO

  def run: ZIO[ZIOAppArgs, Throwable, Seq[String]] =
    val argsGetter: ZIO[ZIOAppArgs, Throwable, Chunk[String]] = getArgs
    for {
      args <- argsGetter
      path <- ZIO.attempt(args.head).tapError(_ => printLine("Missing arg (input file)"))
      result <- run(new File(path))
      _ <- printLine("Found palindromes:")
      _ <- ZStream.fromIterable(result).runForeach(res => printLine(res))
    } yield result
  end run

  def run(f: => File): Task[Seq[String]] =
    IO.attempt(Source.fromFile(f)(Codec.UTF8)).acquireReleaseWith(src => IO.succeed(src.close())) { src =>
      val words: ZStream[Any, Throwable, String] = ZStream.fromIterator(src.getLines())
      val palindromes = words.filterZIO(isPalindrome).filter(_.lengthIs >= 2)
      palindromes.runCollect
        .flatMap(chunk => IO.attempt(chunk.toSeq))
        .flatMap(words => IO.attempt(words.sortBy(word => (-word.length, word))))
    }
  end run

  def isPalindrome(s: String): Task[Boolean] = ZIO.attempt(s.equalsIgnoreCase(s.reverse))

end FindPalindromes
