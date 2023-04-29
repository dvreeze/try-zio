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
 * Repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
trait TermRepo[-R]:

  def findAllTerms(): RIO[R, Seq[Term]]

  def findTerm(termId: Long): RIO[R, Option[Term]]

  def findTermByName(name: String): RIO[R, Option[Term]]

  def findAllTermTaxonomies(): RIO[R, Seq[TermTaxonomy]]

  def findTermTaxonomy(termTaxoId: Long): RIO[R, Option[TermTaxonomy]]

  def findTermTaxonomiesByTermId(termId: Long): RIO[R, Seq[TermTaxonomy]]

  def findTermTaxonomiesByTermName(termName: String): RIO[R, Seq[TermTaxonomy]]

object TermRepo extends TermRepo.AccessorApi:

  // See https://zio.dev/reference/service-pattern/, taken one step further

  type Api = TermRepo[Any]

  type AccessorApi = TermRepo[TermRepo.Api]

  def findAllTerms(): RIO[Api, Seq[Term]] =
    ZIO.serviceWithZIO[Api](_.findAllTerms())

  def findTerm(termId: Long): RIO[Api, Option[Term]] =
    ZIO.serviceWithZIO[Api](_.findTerm(termId))

  def findTermByName(name: String): RIO[Api, Option[Term]] =
    ZIO.serviceWithZIO[Api](_.findTermByName(name))

  def findAllTermTaxonomies(): RIO[Api, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[Api](_.findAllTermTaxonomies())

  def findTermTaxonomy(termTaxoId: Long): RIO[Api, Option[TermTaxonomy]] =
    ZIO.serviceWithZIO[Api](_.findTermTaxonomy(termTaxoId))

  def findTermTaxonomiesByTermId(termId: Long): RIO[Api, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[Api](_.findTermTaxonomiesByTermId(termId))

  def findTermTaxonomiesByTermName(termName: String): RIO[Api, Seq[TermTaxonomy]] =
    ZIO.serviceWithZIO[Api](_.findTermTaxonomiesByTermName(termName))

end TermRepo
