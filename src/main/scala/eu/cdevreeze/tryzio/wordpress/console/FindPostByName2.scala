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
import eu.cdevreeze.tryzio.jdbc.JdbcSupport.*
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2
import javax.sql.DataSource
import zio.*
import zio.Console.*
import zio.json.*

/**
 * Program finding a Post by its name in the Wordpress database, using PostRepoImpl2.
 *
 * @author
 *   Chris de Vreeze
 */
object FindPostByName2 extends ZIOAppDefault:

  def run: Task[Unit] =
    for {
      _ <- printLine("Enter a post name:")
      postName <- readLine
      _ <- printLine("Finding post (if any) for post name $postName:")
      ds <- getDataSource()
      resultOpt <- using(ds).execute(conn => PostRepoImpl2(conn).findPostByName(postName))
      jsonResultOpt <- ZIO.attempt(resultOpt.map(_.toJsonPretty))
      _ <- printLine(jsonResultOpt)
    } yield ()

  // See https://github.com/brettwooldridge/HikariCP for connection pooling

  private def getDataSource(): Task[DataSource] =
    Task.attempt {
      val config = new HikariConfig("/hikari-wp.properties") // Also tries the classpath to read from
      new HikariDataSource(config)
    }

end FindPostByName2
