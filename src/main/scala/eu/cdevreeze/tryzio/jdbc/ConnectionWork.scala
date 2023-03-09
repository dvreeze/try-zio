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

final class QueryForSeq[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E) extends ConnectionWork[Seq[E]]:

  def apply(conn: Connection): Seq[E] =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      args.zipWithIndex.foreach { (arg, index) => arg.applyTo(ps, index + 1) }
      Using.resource(ps.executeQuery()) { rs =>
        Iterator.from(1).takeWhile(_ => rs.next).map(idx => rowMapper(rs, idx)).toSeq
      }
    }

final class QueryForSingleResult[E](sql: String, args: Seq[Argument], rowMapper: (ResultSet, Int) => E) extends ConnectionWork[Option[E]]:

  def apply(conn: Connection): Option[E] =
    QueryForSeq[E](sql, args, rowMapper).apply(conn).headOption
