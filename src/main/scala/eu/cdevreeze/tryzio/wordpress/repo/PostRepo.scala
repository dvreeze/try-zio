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

package eu.cdevreeze.tryzio.wordpress.repo

import eu.cdevreeze.tryzio.wordpress.model.Post
import zio.*

/**
 * Repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
trait PostRepo[-R]:

  def filterPosts(p: Post => Task[Boolean]): RIO[R, Seq[Post]]

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): RIO[R, Seq[Post]]

  def findPost(postId: Long): RIO[R, Option[Post]]

  def findPostByName(name: String): RIO[R, Option[Post]]

object PostRepo extends PostRepo.AccessorApi:

  // See https://zio.dev/reference/service-pattern/, taken one step further

  type Api = PostRepo[Any]

  type AccessorApi = PostRepo[PostRepo.Api]

  def filterPosts(p: Post => Task[Boolean]): RIO[Api, Seq[Post]] =
    ZIO.serviceWithZIO[Api](_.filterPosts(p))

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): RIO[Api, Seq[Post]] =
    ZIO.serviceWithZIO[Api](_.filterPostsReturningNoContent(p))

  def findPost(postId: Long): RIO[Api, Option[Post]] =
    ZIO.serviceWithZIO[Api](_.findPost(postId))

  def findPostByName(name: String): RIO[Api, Option[Post]] =
    ZIO.serviceWithZIO[Api](_.findPostByName(name))

end PostRepo
