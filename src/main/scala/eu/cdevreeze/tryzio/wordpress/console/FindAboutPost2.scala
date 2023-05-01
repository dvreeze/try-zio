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

import java.sql.ResultSet

import zio.*
import zio.Console.*
import zio.jdbc.*
import zio.json.*

/**
 * Much like FindAboutPost, but using lookups instead of "dependency injection". This approach is less desirable than dependency injection,
 * but shown in order to compare them.
 *
 * @author
 *   Chris de Vreeze
 */
object FindAboutPost2 extends ZIOAppDefault:

  final case class Post(
      id: Long,
      postName: String,
      postTitle: String,
      postStatus: String,
      postType: String,
      postContent: String
  )

  private given JdbcDecoder[Post] = JdbcDecoder { (rs: ResultSet) =>
    Post(
      id = rs.getLong(1),
      postName = rs.getString(2),
      postTitle = rs.getString(3),
      postStatus = rs.getString(4),
      postType = rs.getString(5),
      postContent = rs.getString(6)
    )
  }

  private given JsonEncoder[Post] = DeriveJsonEncoder.gen[Post]

  val getEnv: RIO[Scope, ZEnvironment[PostRepo]] =
    val fullLayer: ZLayer[Any, Throwable, PostRepo] =
      ConnectionPools.liveLayer >>> ZLayer.fromFunction(PostRepoImpl(_))
    fullLayer.build

  val program: RIO[Scope, Unit] =
    val postName = "about"
    for {
      env <- getEnv
      postRepo = env.get[PostRepo]
      postOption <- postRepo.findPost(postName)
      jsonResult <- ZIO.attempt(postOption.toJsonPretty)
      _ <- printLine(jsonResult)
    } yield ()

  def run: Task[Unit] = ZIO.scoped(program)

  trait PostRepo:
    def findPost(postName: String): Task[Option[Post]]

  final class PostRepoImpl(val cp: ZConnectionPool) extends PostRepo:
    def findPost(postName: String): Task[Option[Post]] =
      for {
        sql <- ZIO.attempt {
          sql"""select ID, post_name, post_title, post_status, post_type, post_content
          from wp_posts
         where post_name = $postName"""
        }
        postOption <- transaction
          .apply { // Method name "apply" can be left out, of course.
            selectOne(sql.as[Post])
          }
          .provideEnvironment(ZEnvironment(cp))
      } yield postOption

end FindAboutPost2
