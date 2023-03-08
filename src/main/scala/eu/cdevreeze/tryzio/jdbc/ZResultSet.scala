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

import java.sql.ResultSet
import zio.Task
import zio.UIO
import zio.ZIO

/**
 * ResultSet wrapper, inspired by zio-jdbc.
 *
 * @author
 *   Chris de Vreeze
 */
final class ZResultSet(val resultSet: ResultSet):

  def access[A](f: ResultSet => A): Task[A] = ZIO.attemptBlocking { f(resultSet) }

  def close(): UIO[Unit] = ZIO.attempt(resultSet.close()).ignoreLogged
