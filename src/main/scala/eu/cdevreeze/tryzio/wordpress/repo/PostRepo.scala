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
import zio.Task

/**
 * Repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
trait PostRepo:

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]]

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): Task[Seq[Post]]

  def findPost(postId: Long): Task[Option[Post]]

  def findPostByName(name: String): Task[Option[Post]]

end PostRepo
