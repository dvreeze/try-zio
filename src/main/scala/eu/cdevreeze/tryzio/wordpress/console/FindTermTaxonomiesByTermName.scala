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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import eu.cdevreeze.tryzio.jdbc.*
import eu.cdevreeze.tryzio.wordpress.repo.TermRepo
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl
import javax.sql.DataSource
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

  private val dsLayer: TaskLayer[DataSource] = ZLayer.fromZIO(getDataSource())

  def run: Task[Unit] =
    for {
      _ <- printLine("Enter a term name:")
      termName <- readLine
      _ <- printLine("Finding term taxonomies for term name $termName:")
      repo <- ZIO
        .service[TermRepo]
        .provideLayer((dsLayer >>> ZConnectionPoolFromDataSource.layer) >>> TermRepoImpl.layer)
      results <- repo.findTermTaxonomiesByTermName(termName)
      jsonResults <- ZIO.attempt(results.map(_.toJsonPretty))
      _ <- printLine(jsonResults)
    } yield ()

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    ZIO.attempt {
      val config = new HikariConfig("/hikari-wp.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end FindTermTaxonomiesByTermName
