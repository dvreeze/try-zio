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

import zio.*
import zio.Console.*
import zio.jdbc.*
import zio.json.*

import java.sql.ResultSet

/**
 * Very simple example program finding the "about" post in the Wordpress database. It uses the ***zio-jdbc*** library.
 *
 * This compact program showcases an approach towards programming of "business applications" that seems to work well.
 *
 * First of all, the approach is to separate ***data*** (as ADTs) from ***behaviour***, like is typical when combining OO and FP. The data
 * classes are ***immutable***.
 *
 * Secondly, for behaviour we program against ***interfaces***, in Java speak, instead of concrete classes. The concrete classes
 * implementing those interfaces get all their dependencies via the ***constructor***. The constructor parameters are typically of interface
 * types as well. This interface-based approach increases flexibility and testability, and facilitates dependency injection. In this sense,
 * the approach towards programming resembles the approach typical for Spring-based applications.
 *
 * Thirdly, and here's where ZIO comes in, the abstract methods in behaviour/service traits return ***ZIO functional effects***. These
 * functional effects have no requirements, that is, they are of the ZIO [[zio.Task]] type. That ensures that no implementation details are
 * leaked by the abstract behaviour/service interface methods.
 *
 * So far, this looks much the same as for typical Spring-based applications, except that service methods return ZIO functional effects.
 * Hence, services return lazily evaluated recipes for behaviour instead of eagerly performing behaviour. Effects are actually run only in a
 * main program (or some other entrypoint), and up to that point it's just about writing combinable functional effects as immutable values,
 * instead of running any effect.
 *
 * Fourthly, unlike Spring wiring, wiring is done in a very principled type-safe way using ***ZIO layers***, which can be seen as ZIO's
 * "constructors as values". These layers are not used in service constructors themselves, but might or might not be defined in service
 * companion objects. Note that proper resource management is supported well by ZIO layers (and by ZIO effects).
 *
 * Finally, for better ergonomics the (high level) service companion objects implement an ***accessor API***. It is the same as the abstract
 * trait API, except that the methods return ZIO effects that require a service trait implementation to be injected. Just like the combined
 * effect of the program is run at the last moment, after just coding functional effects, the actual wiring can also be postponed to the
 * last moment before that. Using the "accessor API" through service companion objects makes this easy and pleasant to read.
 *
 * @author
 *   Chris de Vreeze
 */
object FindAboutPost extends ZIOAppDefault:

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
      postOption <- PostService.findPost(postName)
      jsonResult <- ZIO.attempt(postOption.toJsonPretty)
      _ <- printLine(jsonResult)
    } yield ()

  val run: Task[Unit] =
    program.provide(ConnectionPools.liveLayer, ZLayer.succeed(PostRepoImpl()), PostServiceImpl.layer)

  // The transactional service (API, accessor API, and implementation)

  trait PostServiceLike[-R]:
    def findPost(postName: String): RIO[R, Option[Post]]

  type PostService = PostServiceLike[Any]

  object PostService extends PostServiceLike[PostService]:
    def findPost(postName: String): RIO[PostService, Option[Post]] =
      ZIO.serviceWithZIO[PostService](_.findPost(postName))

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
        postOption <- sql.query[Post].selectOne
      } yield postOption

    private given JdbcDecoder[Post] = JdbcDecoder { rs => idx =>
      Post(
        id = rs.getLong(1),
        postName = rs.getString(2),
        postTitle = rs.getString(3),
        postStatus = rs.getString(4),
        postType = rs.getString(5),
        postContent = rs.getString(6)
      )
    }

end FindAboutPost
