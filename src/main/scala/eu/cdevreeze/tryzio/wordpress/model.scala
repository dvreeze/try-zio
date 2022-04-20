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

package eu.cdevreeze.tryzio.wordpress

import zio.json.*

/**
 * Model of (part of) Wordpress database data.
 *
 * @author
 *   Chris de Vreeze
 */
object model:

  // Data model for querying

  final case class Term(termId: Long, name: String, slug: String, termGroupOption: Option[Long])

  object Term:
    given decoder: JsonDecoder[Term] = DeriveJsonDecoder.gen[Term]
    given encoder: JsonEncoder[Term] = DeriveJsonEncoder.gen[Term]

  final case class TermTaxonomy(
      termTaxonomyId: Long,
      term: Term,
      taxonomy: String,
      description: String,
      children: Seq[TermTaxonomy],
      count: Int
  )

  object TermTaxonomy:
    given decoder: JsonDecoder[TermTaxonomy] = DeriveJsonDecoder.gen[TermTaxonomy]
    given encoder: JsonEncoder[TermTaxonomy] = DeriveJsonEncoder.gen[TermTaxonomy]

end model
