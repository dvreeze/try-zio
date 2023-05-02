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

import eu.cdevreeze.tryzio.wordpress.model.*
import zio.*
import zio.jdbc.*

/**
 * Concrete repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
final class TermServiceImpl(val cp: ZConnectionPool, val repo: TermRepo) extends TermService:

  def findAllTerms(): Task[Seq[Term]] =
    for {
      terms <- transaction {
        repo.findAllTerms()
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield terms

  def findTerm(termId: Long): Task[Option[Term]] =
    for {
      termOption <- transaction {
        repo.findTerm(termId)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termOption

  def findTermByName(name: String): Task[Option[Term]] =
    for {
      termOption <- transaction {
        findTermByName(name)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termOption

  def findAllTermTaxonomies(): Task[Seq[TermTaxonomy]] =
    for {
      termTaxos <- transaction {
        repo.findAllTermTaxonomies()
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termTaxos

  def findTermTaxonomy(termTaxoId: Long): Task[Option[TermTaxonomy]] =
    for {
      termTaxos <- transaction {
        repo.findTermTaxonomy(termTaxoId)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termTaxos
  end findTermTaxonomy

  def findTermTaxonomiesByTermId(termId: Long): Task[Seq[TermTaxonomy]] =
    for {
      termTaxos <- transaction {
        repo.findTermTaxonomiesByTermId(termId)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termTaxos
  end findTermTaxonomiesByTermId

  def findTermTaxonomiesByTermName(termName: String): Task[Seq[TermTaxonomy]] =
    for {
      termTaxos <- transaction {
        repo.findTermTaxonomiesByTermName(termName)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termTaxos
  end findTermTaxonomiesByTermName

object TermServiceImpl:

  val layer: ZLayer[ZConnectionPool & TermRepo, Nothing, TermServiceImpl] =
    ZLayer {
      for {
        cp <- ZIO.service[ZConnectionPool]
        repo <- ZIO.service[TermRepo]
      } yield TermServiceImpl(cp, repo)
    }

end TermServiceImpl
