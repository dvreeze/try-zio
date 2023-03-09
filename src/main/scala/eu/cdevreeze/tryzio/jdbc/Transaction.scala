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

import zio.Task

/**
 * Database (local) transaction, returning a value of type A.
 *
 * TODO Readonly transactions TODO Make when to rollback configurable
 *
 * @author
 *   Chris de Vreeze
 */
trait Transaction[A](val isolationLevel: Int) extends ((Connection => A) => Task[A]):

  def apply(f: Connection => A): Task[A]
