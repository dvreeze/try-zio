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

import java.sql.Connection
import java.sql.ResultSet

import scala.util.Using

import eu.cdevreeze.tryzio.jdbc.JdbcSupport.Argument
import zio.RIO
import zio.ZIO

/**
 * Side-effecting work taking a Connection.
 *
 * Note that JDBC code typically must run in a blocking way, using one single thread for each (transactional) use of a Connection. Hence the
 * blocking effects around database work. In the case of the well-known Connector/J MySQL JDBC driver, see for example the discussion at
 * https://bugs.mysql.com/bug.php?id=67760.
 *
 * @author
 *   Chris de Vreeze
 */
type ConnectionWork[A] = RIO[ZConnection, A]

object ConnectionWork:

  def queryForSeq[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E): ConnectionWork[Seq[E]] =
    query(
      sql,
      args,
      { rs => Iterator.from(1).takeWhile(_ => rs.next).map(idx => rowMapper(rs, idx)).toSeq }
    )

  def queryForSingleResult[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E): ConnectionWork[Option[E]] =
    queryForSeq[E](sql, args, rowMapper).map(_.headOption)

  def query[A](sql: String, args: Seq[Argument], resultSetMapper: ResultSet => A): ConnectionWork[A] =
    ZIO.blocking {
      ZIO.scoped {
        ZIO.acquireReleaseWith {
          ZIO.service[ZConnection].flatMap(conn => ZIO.attempt(conn.connection.prepareStatement(sql)))
        } { ps =>
          ZIO.attempt(ps.close()).ignore
        } { ps =>
          for {
            _ <- ZIO.foreachDiscard(args.zipWithIndex) { (arg, index) => ZIO.attempt(arg.applyTo(ps, index + 1)) }
            result <- ZIO.acquireReleaseWith {
              ZIO.attempt(ps.executeQuery())
            } { rs =>
              ZIO.attempt(rs.close()).ignore
            } { rs =>
              ZIO.attempt(resultSetMapper(rs))
            }
          } yield result
        }
      }
    }

  def update(sql: String, args: Seq[Argument]): ConnectionWork[Int] =
    ZIO.blocking {
      ZIO.scoped {
        ZIO.acquireReleaseWith {
          ZIO.service[ZConnection].flatMap(conn => ZIO.attempt(conn.connection.prepareStatement(sql)))
        } { ps =>
          ZIO.attempt(ps.close()).ignore
        } { ps =>
          for {
            _ <- ZIO.foreachDiscard(args.zipWithIndex) { (arg, index) => ZIO.attempt(arg.applyTo(ps, index + 1)) }
            cnt <- ZIO.attempt(ps.executeUpdate())
          } yield cnt
        }
      }
    }
