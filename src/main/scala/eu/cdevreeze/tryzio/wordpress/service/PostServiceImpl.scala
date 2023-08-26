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

package eu.cdevreeze.tryzio.wordpress.service

import eu.cdevreeze.tryzio.wordpress.model.*
import eu.cdevreeze.tryzio.wordpress.repo.PostRepo
import zio.*
import zio.jdbc.*

/**
 * Concrete repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostServiceImpl(val cp: ZConnectionPool, val repo: PostRepo) extends PostService:

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    for {
      posts <- transaction {
        repo.filterPosts(p)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield posts

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    filterPosts(p).map(_.map(_.copy(postContentOption = None).copy(postContentFilteredOption = None)))

  def findPost(postId: Long): Task[Option[Post]] =
    for {
      posts <- transaction {
        repo.findPost(postId)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield posts
  end findPost

  def findPostByName(name: String): Task[Option[Post]] =
    for {
      posts <- transaction {
        repo.findPostByName(name)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield posts
  end findPostByName

object PostServiceImpl:

  val layer: ZLayer[ZConnectionPool & PostRepo, Nothing, PostServiceImpl] =
    ZLayer {
      for {
        cp <- ZIO.service[ZConnectionPool]
        repo <- ZIO.service[PostRepo]
      } yield PostServiceImpl(cp, repo)
    }

end PostServiceImpl
