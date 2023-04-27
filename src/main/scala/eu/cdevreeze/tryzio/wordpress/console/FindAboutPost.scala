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
 * Program finding the "about" post in the Wordpress database. It uses the zio-jdbc library.
 *
 * @author
 *   Chris de Vreeze
 */
object FindAboutPost extends ZIOAppDefault:

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

  def findPost(postName: String): ZIO[ZConnectionPool, Throwable, Option[Post]] =
    for {
      postName <- ZIO.succeed(postName)
      sql <- ZIO.attempt {
        sql"""select ID, post_name, post_title, post_status, post_type, post_content
                from wp_posts
               where post_name = $postName"""
      }
      postOption <- transaction {
        selectOne(sql.as[Post])
      }
    } yield postOption

  def run: Task[Unit] =
    for {
      postName <- ZIO.succeed("about")
      postOption <- findPost(postName).provideLayer(ConnectionPools.liveLayer)
      jsonResult <- ZIO.attempt(postOption.toJsonPretty)
      _ <- printLine(jsonResult)
    } yield ()

end FindAboutPost
