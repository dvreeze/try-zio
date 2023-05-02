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

import eu.cdevreeze.tryzio.wordpress.model.Term
import eu.cdevreeze.tryzio.wordpress.model.TermTaxonomy
import zio.*

/**
 * Transactional service for terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
trait TermServiceLike[-R]:

  def findAllTerms(): RIO[R, Seq[Term]]

  def findTerm(termId: Long): RIO[R, Option[Term]]

  def findTermByName(name: String): RIO[R, Option[Term]]

  def findAllTermTaxonomies(): RIO[R, Seq[TermTaxonomy]]

  def findTermTaxonomy(termTaxoId: Long): RIO[R, Option[TermTaxonomy]]

  def findTermTaxonomiesByTermId(termId: Long): RIO[R, Seq[TermTaxonomy]]

  def findTermTaxonomiesByTermName(termName: String): RIO[R, Seq[TermTaxonomy]]

end TermServiceLike

type TermService = TermServiceLike[Any]

object TermService extends TermServiceLike[TermService]:

  // See https://zio.dev/reference/service-pattern/, taken one step further

  def findAllTerms(): RIO[TermService, Seq[Term]] =
    ZIO.serviceWithZIO[TermService](_.findAllTerms())

  def findTerm(termId: Long): RIO[TermService, Option[Term]] =
    ZIO.serviceWithZIO[TermService](_.findTerm(termId))

  def findTermByName(name: String): RIO[TermService, Option[Term]] =
    ZIO.serviceWithZIO[TermService](_.findTermByName(name))

  def findAllTermTaxonomies(): RIO[TermService, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[TermService](_.findAllTermTaxonomies())

  def findTermTaxonomy(termTaxoId: Long): RIO[TermService, Option[TermTaxonomy]] =
    ZIO.serviceWithZIO[TermService](_.findTermTaxonomy(termTaxoId))

  def findTermTaxonomiesByTermId(termId: Long): RIO[TermService, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[TermService](_.findTermTaxonomiesByTermId(termId))

  def findTermTaxonomiesByTermName(termName: String): RIO[TermService, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[TermService](_.findTermTaxonomiesByTermName(termName))

end TermService
