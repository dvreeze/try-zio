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
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.chaining.*

import javax.sql.DataSource
import zio.*

/**
 * Naive ZIO-based JDBC support, somewhat inspired by Spring JDBC support, but quite minimal and trying to be more "functional". It is also
 * quite "spartan" in that layers of transactions, connections, statements etc. are "in your face".
 *
 * Note that JDBC code typically must run in a blocking way, using one single thread for each (transactional) use of a Connection.
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupport:

  type ScopedTask[A] = RIO[Scope, A]

  final class Transaction(val connection: Connection, val rollbackOnly: AtomicBoolean):
    def isolationLevel: Int = connection.getTransactionIsolation
    def rollback(): Unit = connection.rollback()
    def commit(): Unit = connection.commit()
    def onlyRollback(): Unit = rollbackOnly.set(true)

  object Transaction:
    def unsafe(conn: Connection): Transaction = new Transaction(conn, rollbackOnly = AtomicBoolean(false))
    def start(conn: Connection, isolationLevel: Int): Transaction =
      new Transaction(
        conn.tap(_.setAutoCommit(false)).tap(_.setTransactionIsolation(isolationLevel)),
        rollbackOnly = AtomicBoolean(false)
      )

  end Transaction

  final case class TransactionConfig(isolationLevel: Int):
    def withIsolationLevel(newIsolationLevel: Int): TransactionConfig =
      this.copy(isolationLevel = newIsolationLevel)

  enum Argument(val arg: AnyRef, val argType: Int):
    case TypedArg(override val arg: AnyRef, override val argType: Int) extends Argument(arg, argType)
    case UntypedArg(override val arg: AnyRef) extends Argument(arg, Types.OTHER)

  // TODO Make exception types for which to rollback configurable

  final class Transactional(val ds: DataSource, val config: TransactionConfig):
    def withIsolationLevel(newIsolationLevel: Int): Transactional =
      Transactional(ds, config.withIsolationLevel(newIsolationLevel))

    def execute[A](f: Transaction => Task[A]): Task[A] =
      execute(startTransaction())(f)

    def execute[A](acquireTx: => Task[Transaction])(f: Transaction => Task[A]): Task[A] =
      val scopedTask: ScopedTask[Transaction] =
        ZIO.acquireRelease(acquireTx)(tx => finishTransaction(tx))

      ZIO.scoped {
        scopedTask.flatMap { tx =>
          f(tx).tapError(_ => IO.succeed(tx.onlyRollback()))
        }
      }
    end execute

    // TODO Improve the functions below, and also make isolation level an enum

    private def startTransaction(): Task[Transaction] =
      Task.attempt {
        val conn = ds.getConnection()
        Transaction.start(conn, config.isolationLevel)
      }

    private def finishTransaction(tx: Transaction): UIO[Unit] =
      val commitOrRollback: Task[Unit] = Task.attempt { if tx.rollbackOnly.get then tx.rollback() else tx.commit() }
      commitOrRollback.catchAll(_ => Task.succeed(())).ensuring(Task.succeed(tx.connection.close()))
  end Transactional

  final class UsingDataSource(val ds: DataSource):
    def execute[A](f: Connection => Task[A]): Task[A] =
      execute(Task.attempt(ds.getConnection))(f)

    def execute[A](acquireConn: => Task[Connection])(f: Connection => Task[A]): Task[A] =
      val scopedTask: ScopedTask[Connection] =
        ZIO.acquireRelease(acquireConn)(conn => Task.succeed(conn.close()))
      ZIO.scoped(scopedTask.flatMap(f))
  end UsingDataSource

  final class UsingConnection(val conn: Connection):
    def execute[A](sqlString: String, args: Seq[Argument])(f: PreparedStatement => Task[A]): Task[A] =
      execute(createPreparedStatement(sqlString, args))(f)

    def execute[A](acquirePs: => Task[PreparedStatement])(f: PreparedStatement => Task[A]): Task[A] =
      val scopedTask: ScopedTask[PreparedStatement] =
        ZIO.acquireRelease(acquirePs)(ps => Task.succeed(ps.close()))
      ZIO.scoped(scopedTask.flatMap(f))

    def query[A](sqlString: String, args: Seq[Argument])(rowMapper: (ResultSet, Int) => A): Task[Seq[A]] =
      execute(sqlString, args)(ps => queryForResults(ps, rowMapper))

    private def createPreparedStatement(sqlString: String, args: Seq[Argument]): Task[PreparedStatement] =
      Task.attempt {
        val ps = conn.prepareStatement(sqlString)
        args.zipWithIndex.foreach { (arg, index) =>
          ps.setObject(index + 1, arg.arg, arg.argType)
        }
        ps
      }

    private def queryForResults[A](ps: PreparedStatement, rowMapper: (ResultSet, Int) => A): Task[Seq[A]] =
      // Database query is run in "acquire ResultSet" step. Is that ok?
      val scopedTask: ScopedTask[ResultSet] =
        ZIO.acquireRelease(Task.attempt(ps.executeQuery()))(rs => Task.succeed(rs.close()))
      ZIO.scoped {
        scopedTask.flatMap { rs =>
          Task.attempt {
            Iterator.from(1).takeWhile(_ => rs.next).map(idx => rowMapper(rs, idx)).toSeq // 1-based?
          }
        }
      }
  end UsingConnection

  // Entrypoints of this API

  def transactional(ds: DataSource, config: TransactionConfig): Transactional = Transactional(ds, config)

  def use(ds: DataSource): UsingDataSource = UsingDataSource(ds)

  def use(conn: Connection): UsingConnection = UsingConnection(conn)

end JdbcSupport
