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

import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2
import eu.cdevreeze.tryzio.wordpress.service.{PostService, PostServiceImpl}
import zio.*
import zio.Console.*
import zio.json.*

/**
 * Program finding all posts in the Wordpress database, using PostRepoImpl2.
 *
 * @author
 *   Chris de Vreeze
 */
object FindPosts2 extends ZIOAppDefault:

  val program: ZIO[PostService, Throwable, Unit] =
    for {
      results <- PostService.filterPosts(_ => ZIO.succeed(true))
      jsonResults <- ZIO.attempt(results.map(_.toJsonPretty))
      _ <- printLine(jsonResults)
    } yield ()

  val run: Task[Unit] =
    program.provide(ConnectionPools.liveLayer, PostRepoImpl2.layer, PostServiceImpl.layer)

end FindPosts2
