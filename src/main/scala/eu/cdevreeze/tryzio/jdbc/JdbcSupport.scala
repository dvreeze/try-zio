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

import scala.util.Try
import scala.util.chaining.*

import javax.sql.DataSource
import zio.*

/**
 * Naive ZIO-based JDBC support, somewhat inspired by Spring JDBC support, but quite minimal and trying to be more "functional". It is also
 * quite "spartan" in that layers of transactions, connections, statements etc. are "in your face".
 *
 * Note that JDBC code typically must run in a blocking way, using one single thread for each (transactional) use of a Connection. So, by
 * all means, wrap any ZIO effect doing database work in ZIO.blocking.
 *
 * @author
 *   Chris de Vreeze
 */
object JdbcSupport:

  // TODO Follow ZIO 2.0 best practices, like using by-name parameters when ZIO effects are returned
  // TODO Enhance and improve the API, for example by taking more control over blocking

  type ScopedTask[A] = RIO[Scope, A]

  final class Transaction(val connection: Connection, val rollbackOnly: AtomicBoolean):
    def isolationLevel: IsolationLevel = IsolationLevel.from(connection.getTransactionIsolation)
    def rollback(): Unit = connection.rollback()
    def commit(): Unit = connection.commit()
    def onlyRollback(): Unit = rollbackOnly.set(true)

  object Transaction:
    def unsafe(conn: Connection): Transaction = new Transaction(conn, rollbackOnly = AtomicBoolean(false))
    def start(conn: Connection, isolationLevel: IsolationLevel): Transaction =
      new Transaction(
        conn.tap(_.setAutoCommit(false)).tap(_.setTransactionIsolation(isolationLevel.intValue)),
        rollbackOnly = AtomicBoolean(false)
      )

  end Transaction

  final case class TransactionConfig(isolationLevel: IsolationLevel):
    def withIsolationLevel(newIsolationLevel: IsolationLevel): TransactionConfig =
      this.copy(isolationLevel = newIsolationLevel)

  enum IsolationLevel(val intValue: Int):
    case NoTransactions extends IsolationLevel(Connection.TRANSACTION_NONE)
    case ReadUncommited extends IsolationLevel(Connection.TRANSACTION_READ_UNCOMMITTED)
    case ReadCommitted extends IsolationLevel(Connection.TRANSACTION_READ_COMMITTED)
    case RepeatableRead extends IsolationLevel(Connection.TRANSACTION_REPEATABLE_READ)
    case Serializable extends IsolationLevel(Connection.TRANSACTION_SERIALIZABLE)

  object IsolationLevel:
    def from(intValue: Int): IsolationLevel =
      intValue match
        case Connection.TRANSACTION_NONE             => IsolationLevel.NoTransactions
        case Connection.TRANSACTION_READ_UNCOMMITTED => IsolationLevel.ReadUncommited
        case Connection.TRANSACTION_READ_COMMITTED   => IsolationLevel.ReadCommitted
        case Connection.TRANSACTION_REPEATABLE_READ  => IsolationLevel.RepeatableRead
        case Connection.TRANSACTION_SERIALIZABLE     => IsolationLevel.Serializable

  // Far from complete at the moment
  enum Argument(val arg: Any):
    case StringArg(override val arg: String) extends Argument(arg)
    case BigDecimalArg(override val arg: BigDecimal) extends Argument(arg)
    case BooleanArg(override val arg: Boolean) extends Argument(arg)
    case ByteArg(override val arg: Byte) extends Argument(arg)
    case BytesArg(override val arg: Array[Byte]) extends Argument(arg)
    case IntArg(override val arg: Int) extends Argument(arg)
    case LongArg(override val arg: Long) extends Argument(arg)
    case NullArg(val sqlType: Int) extends Argument(None)
    case AnyRefArg(override val arg: AnyRef, val sqlType: Int) extends Argument(arg)
    case ShortArg(override val arg: Short) extends Argument(arg)

    def useOn(ps: PreparedStatement, idx: Int): Unit =
      this match
        case StringArg(v)          => ps.setString(idx, v)
        case BigDecimalArg(v)      => ps.setBigDecimal(idx, v.bigDecimal)
        case BooleanArg(v)         => ps.setBoolean(idx, v)
        case ByteArg(v)            => ps.setByte(idx, v)
        case BytesArg(v)           => ps.setBytes(idx, v)
        case IntArg(v)             => ps.setInt(idx, v)
        case LongArg(v)            => ps.setLong(idx, v)
        case NullArg(sqlType)      => ps.setNull(idx, sqlType)
        case AnyRefArg(v, sqlType) => ps.setObject(idx, v, sqlType)
        case ShortArg(v)           => ps.setShort(idx, v)

  end Argument

  // TODO Make exception types for which to rollback configurable

  final class Transactional(val ds: DataSource, val config: TransactionConfig):
    def withIsolationLevel(newIsolationLevel: IsolationLevel): Transactional =
      Transactional(ds, config.withIsolationLevel(newIsolationLevel))

    def execute[A](f: Transaction => Task[A]): Task[A] =
      execute(startTransaction())(f)

    def execute[A](acquireTx: Task[Transaction])(f: Transaction => Task[A]): Task[A] =
      val manageTx: ScopedTask[Transaction] =
        ZIO.acquireRelease(acquireTx)(tx => finishTransaction(tx))

      ZIO.scoped {
        manageTx.flatMap { tx =>
          f(tx)
            .tapError(_ => IO.succeed(tx.onlyRollback()))
        }
      }
    end execute

    // TODO Improve the functions below

    private def startTransaction(): Task[Transaction] =
      Task.attempt {
        val conn = ds.getConnection()
        Transaction.start(conn, config.isolationLevel)
      }

    private def finishTransaction(tx: Transaction): UIO[Unit] =
      Task.fromTry {
        Try {
          if tx.rollbackOnly.get then tx.rollback() else tx.commit()
        }.recover(_ => ())
          .flatMap(_ => Try(tx.connection.close()))
          .recover(_ => ())
      }.orDie
  end Transactional

  final class UsingDataSource(val ds: DataSource):
    def execute[A](f: Connection => Task[A]): Task[A] =
      execute(Task.attempt(ds.getConnection))(f)

    def execute[A](acquireConn: Task[Connection])(f: Connection => Task[A]): Task[A] =
      val manageConn: ScopedTask[Connection] =
        ZIO.acquireRelease(acquireConn)(conn => Task.succeed(conn.close()))
      ZIO.scoped(manageConn.flatMap(f))
  end UsingDataSource

  final class UsingConnection(val conn: Connection):
    def executeStatement[A](sqlString: String, args: Seq[Argument]): Task[Boolean] =
      execute(sqlString, args)(ps => Task.attempt(ps.execute()))

    def query[A](sqlString: String, args: Seq[Argument])(rowMapper: (ResultSet, Int) => A): Task[Seq[A]] =
      execute(sqlString, args)(ps => queryForResults(ps, rowMapper))

    private def execute[A](sqlString: String, args: Seq[Argument])(f: PreparedStatement => Task[A]): Task[A] =
      execute(createPreparedStatement(sqlString, args))(f)

    private def execute[A](acquirePs: Task[PreparedStatement])(f: PreparedStatement => Task[A]): Task[A] =
      val managePs: ScopedTask[PreparedStatement] =
        ZIO.acquireRelease(acquirePs)(ps => Task.succeed(ps.close()))
      ZIO.scoped(managePs.flatMap(f))

    private def createPreparedStatement(sqlString: String, args: Seq[Argument]): Task[PreparedStatement] =
      Task.attempt {
        val ps = conn.prepareStatement(sqlString)
        args.zipWithIndex.foreach { (arg, index) => arg.useOn(ps, index + 1) }
        ps
      }

    private def queryForResults[A](ps: PreparedStatement, rowMapper: (ResultSet, Int) => A): Task[Seq[A]] =
      // Database query is run in "acquire ResultSet" step. Is that ok?
      val manageRs: ScopedTask[ResultSet] =
        ZIO.acquireRelease(Task.attempt(ps.executeQuery()))(rs => Task.succeed(rs.close()))
      ZIO.scoped {
        manageRs.flatMap { rs =>
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
