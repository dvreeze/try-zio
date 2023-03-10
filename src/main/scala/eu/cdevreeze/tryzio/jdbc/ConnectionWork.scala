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
trait ConnectionWork[A] extends (Connection => A):

  def apply(conn: Connection): A

object ConnectionWork:

  def queryForSeq[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E): QueryForSeq[E] =
    QueryForSeq[E](sql, args, rowMapper)

  def queryForSingleResult[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E): QueryForSingleResult[E] =
    QueryForSingleResult[E](sql, args, rowMapper)

  def query[A](sql: String, args: Seq[Argument], resultSetMapper: ResultSet => A): Query[A] = Query[A](sql, args, resultSetMapper)

  def update(sql: String, args: Seq[Argument]): Update = Update(sql, args)

final class QueryForSeq[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E) extends ConnectionWork[Seq[E]]:

  def apply(conn: Connection): Seq[E] =
    Query(
      sql,
      args,
      { rs => Iterator.from(1).takeWhile(_ => rs.next).map(idx => rowMapper(rs, idx)).toSeq }
    ).apply(conn)

final class QueryForSingleResult[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E) extends ConnectionWork[Option[E]]:

  def apply(conn: Connection): Option[E] =
    QueryForSeq[E](sql, args, rowMapper).apply(conn).headOption

final class Query[A](sql: String, args: Seq[Argument], resultSetMapper: ResultSet => A) extends ConnectionWork[A]:

  def apply(conn: Connection): A =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      args.zipWithIndex.foreach { (arg, index) => arg.applyTo(ps, index + 1) }
      Using.resource(ps.executeQuery())(resultSetMapper)
    }

final class Update(sql: String, args: Seq[Argument]) extends ConnectionWork[Int]:

  def apply(conn: Connection): Int =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      args.zipWithIndex.foreach { (arg, index) => arg.applyTo(ps, index + 1) }
      ps.executeUpdate()
    }
