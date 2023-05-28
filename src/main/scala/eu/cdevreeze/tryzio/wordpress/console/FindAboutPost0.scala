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
 * First iteration of what turned into program FindAboutPost.
 *
 * @author
 *   Chris de Vreeze
 */
object FindAboutPost0 extends ZIOAppDefault:

  // Data

  final case class Post(
      id: Long,
      postName: String,
      postTitle: String,
      postStatus: String,
      postType: String,
      postContent: String
  )

  private given JsonEncoder[Post] = DeriveJsonEncoder.gen[Post]

  // The program

  val program: ZIO[PostService, Throwable, Unit] =
    val postName = "about"
    for {
      postOption <- ZIO.serviceWithZIO[PostService](_.findPost(postName))
      jsonResult <- ZIO.attempt(postOption.toJsonPretty)
      _ <- printLine(jsonResult)
    } yield ()

  def run: Task[Unit] =
    program.provide(ConnectionPools.liveLayer, ZLayer.succeed(PostRepoImpl()), PostServiceImpl.layer)

  // The transactional service (API, and implementation)

  trait PostService:
    def findPost(postName: String): Task[Option[Post]]

  final class PostServiceImpl(val cp: ZConnectionPool, val repo: PostRepo) extends PostService:
    def findPost(postName: String): Task[Option[Post]] =
      // Note that transaction is a ZLayer taking a long-lived ZConnectionPool and returning a new short-lived ZConnection
      transaction
        .apply { // The explicit apply method call could be left out, of course
          repo.findPost(postName)
        }
        .provideEnvironment(ZEnvironment(cp))

  object PostServiceImpl:
    val layer: ZLayer[ZConnectionPool & PostRepo, Nothing, PostServiceImpl] =
      ZLayer {
        for {
          cp <- ZIO.service[ZConnectionPool]
          repo <- ZIO.service[PostRepo]
        } yield PostServiceImpl(cp, repo)
      }

  // The lower level repository, where the connection can be seen as part of the semantics (API and implementation)

  trait PostRepo:
    def findPost(postName: String): RIO[ZConnection, Option[Post]]

  final class PostRepoImpl() extends PostRepo:
    def findPost(postName: String): RIO[ZConnection, Option[Post]] =
      for {
        sql <- ZIO.attempt {
          sql"""select ID, post_name, post_title, post_status, post_type, post_content
          from wp_posts
         where post_name = $postName"""
        }
        postOption <- selectOne(sql.as[Post])
      } yield postOption

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

end FindAboutPost0
