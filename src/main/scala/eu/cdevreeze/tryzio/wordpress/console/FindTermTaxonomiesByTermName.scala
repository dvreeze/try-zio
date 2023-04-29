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

package eu.cdevreeze.tryzio.wordpress.console

import eu.cdevreeze.tryzio.wordpress.repo.TermRepo
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl
import zio.*
import zio.Console.*
import zio.json.*

/**
 * Program finding the term taxonomies with the given term name in the Wordpress database.
 *
 * @author
 *   Chris de Vreeze
 */
object FindTermTaxonomiesByTermName extends ZIOAppDefault:

  val program: ZIO[TermRepo.Api, Throwable, Unit] =
    for {
      _ <- printLine("Enter a term name:")
      termName <- readLine
      _ <- printLine(s"Finding term taxonomies for term name '$termName':")
      results <- TermRepo.findTermTaxonomiesByTermName(termName)
      jsonResults <- ZIO.attempt(results.map(_.toJsonPretty))
      _ <- printLine(jsonResults)
    } yield ()

  def run: Task[Unit] =
    program.provide(ConnectionPools.liveLayer, ZLayer.fromFunction(TermRepoImpl(_)))

end FindTermTaxonomiesByTermName
