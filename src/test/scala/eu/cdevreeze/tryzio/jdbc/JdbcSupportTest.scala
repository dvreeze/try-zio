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

package eu.cdevreeze.tryzio.jdbc

import org.testcontainers.containers.MySQLContainer
import zio.*
import zio.Console.printLine
import zio.jdbc.*
import zio.test.Assertion.*
import zio.test.{Spec, ZIOSpecDefault, assert}

import scala.util.chaining.*

/**
 * JDBC support test, using Testcontainers (MySQL).
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupportTest extends ZIOSpecDefault:

  private final case class User(host: String, user: String)

  private final case class Timezone(id: Int, name: String, useLeapSeconds: Boolean)

  private given JdbcDecoder[User] = JdbcDecoder { rs => idx =>
    User(rs.getString(1), rs.getString(2))
  }

  private given JdbcDecoder[Timezone] = JdbcDecoder { rs => idx =>
    Timezone(rs.getInt(1), rs.getString(2), rs.getString(3) == "Y")
  }

  private val rawSpec: Spec[MySQLTestContainer, Throwable] = suite("ZIO-based JDBC Support test") {
    // Probably wasteful to start connection pool on each test.

    List(
      test("Querying for users succeeds") {
        for {
          _ <- ZIO.service[MySQLTestContainer]
          result <- findAllUsers()
            .tap(res => printLine(s"Users: $res"))
        } yield assert(result.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying multiple times simultaneously for users succeeds") {
        for {
          _ <- ZIO.service[MySQLTestContainer]
          multipleResults <- findAllUsers()
            .pipe(getDs => ZIO.foreachPar(0.until(5))(_ => getDs))
        } yield assert(multipleResults.flatten.map(_.host).distinct)(equalTo(Seq("%", "localhost")))
      },
      test("Querying for timezones succeeds") {
        for {
          _ <- ZIO.service[MySQLTestContainer]
          result <- getSomeTimezones("Europe/%")
            .tap(res => printLine(s"Timezones: $res"))
        } yield {
          assert(result.map(_.name))(contains("Europe/Lisbon")) &&
          assert(result.map(_.name))(contains("Europe/Berlin")) &&
          assert(result.map(_.name))(contains("Europe/Moscow")) &&
          assert(result.map(_.name))(contains("Europe/Amsterdam"))
        }
      },
      test("Querying multiple times simultaneously for timezones succeeds") {
        for {
          _ <- ZIO.service[MySQLTestContainer]
          multipleResults <- getSomeTimezones("Europe/%")
            .pipe(getDs => ZIO.foreachPar(0.until(5))(_ => getDs))
        } yield {
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Lisbon")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Berlin")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Moscow")) &&
          assert(multipleResults.flatten.map(_.name))(contains("Europe/Amsterdam"))
        }
      }
    )
  }

  val spec: Spec[Any, Throwable] = rawSpec.provideLayerShared(Containers.testContainerLayer)

  private def findAllUsers(): Task[Seq[User]] =
    for {
      sqlQuery <- ZIO.attempt {
        sql"select host, user from user"
      }
      result <- transaction {
        sqlQuery.query[User].selectAll
      }
        .provideLayer(ConnectionPools.testLayer)
    } yield result
  end findAllUsers

  private def getSomeTimezones(timezoneLikeString: String): Task[Seq[Timezone]] =
    val sqlQueryTask: Task[SqlFragment] = ZIO.attempt {
      sql"""
           select time_zone.time_zone_id, time_zone_name.name, time_zone.use_leap_seconds
             from time_zone
             join time_zone_name on time_zone.time_zone_id = time_zone_name.time_zone_id
            where time_zone_name.name like $timezoneLikeString
         """
    }

    for {
      sqlQuery <- sqlQueryTask
      result <- transaction {
        sqlQuery.query[Timezone].selectAll
      }
        .provideLayer(ConnectionPools.testLayer)
    } yield result
  end getSomeTimezones

  final class MySQLTestContainer extends MySQLContainer[MySQLTestContainer]("mysql:5.7")

  object Containers:

    val testContainerLayer: ULayer[MySQLTestContainer] =
      ZLayer.scoped {
        ZIO.acquireRelease {
          ZIO.attempt(new MySQLTestContainer())
            .tap(container => ZIO.attempt(container.withInitScript("loadTestData.sql")))
            .tap(container => ZIO.attempt(container.start()))
            .orDie
        } { container =>
          ZIO.attempt(container.stop()).ignoreLogged
        }
      }

  end Containers

  object ConnectionPools:

    val zioPoolConfigLayer: ULayer[ZConnectionPoolConfig] =
      ZLayer.succeed(ZConnectionPoolConfig.default)

    val connectionPoolLayer: ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
      Containers.testContainerLayer.flatMap { container =>
        ZConnectionPool.mysql(
          host = container.get.getHost,
          port = container.get.getFirstMappedPort,
          database = container.get.getDatabaseName,
          props = Map("user" -> container.get.getUsername, "password" -> container.get.getPassword)
        )
      }

    val testLayer: ZLayer[Any, Throwable, ZConnectionPool] = zioPoolConfigLayer >>> connectionPoolLayer

  end ConnectionPools

end JdbcSupportTest
