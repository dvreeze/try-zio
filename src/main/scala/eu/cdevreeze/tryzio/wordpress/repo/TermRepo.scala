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
import zio.Task

/**
 * Repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
trait TermRepo:

  def findAllTerms(): Task[Seq[Term]]

  def findTerm(termId: Long): Task[Option[Term]]

  def findTermByName(name: String): Task[Option[Term]]

  def findAllTermTaxonomies(): Task[Seq[TermTaxonomy]]

  def findTermTaxonomy(termTaxoId: Long): Task[Option[TermTaxonomy]]

  def findTermTaxonomiesByTermId(termId: Long): Task[Seq[TermTaxonomy]]

  def findTermTaxonomiesByTermName(termName: String): Task[Seq[TermTaxonomy]]

end TermRepo
