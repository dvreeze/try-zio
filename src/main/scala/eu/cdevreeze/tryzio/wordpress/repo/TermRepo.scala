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
import zio.jdbc.ZConnection

/**
 * Repository of terms and term taxonomies. In this low-level repository, the required ZConnection is part of the semantics.
 *
 * @author
 *   Chris de Vreeze
 */
trait TermRepo:

  def findAllTerms(): RIO[ZConnection, Seq[Term]]

  def findTerm(termId: Long): RIO[ZConnection, Option[Term]]

  def findTermByName(name: String): RIO[ZConnection, Option[Term]]

  def findAllTermTaxonomies(): RIO[ZConnection, Seq[TermTaxonomy]]

  def findTermTaxonomy(termTaxoId: Long): RIO[ZConnection, Option[TermTaxonomy]]

  def findTermTaxonomiesByTermId(termId: Long): RIO[ZConnection, Seq[TermTaxonomy]]

  def findTermTaxonomiesByTermName(termName: String): RIO[ZConnection, Seq[TermTaxonomy]]

end TermRepo
