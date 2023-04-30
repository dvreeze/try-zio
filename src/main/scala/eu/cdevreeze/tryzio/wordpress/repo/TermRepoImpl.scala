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

import java.sql.Connection
import java.sql.ResultSet

import scala.util.chaining.*

import eu.cdevreeze.tryzio.wordpress.model.*
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermRow
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermTaxonomyRow
import zio.*
import zio.jdbc.*

/**
 * Concrete repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
final class TermRepoImpl(val cp: ZConnectionPool) extends TermRepo.Api:

  private def baseTermSql: SqlFragment =
    sql"select wp_terms.term_id, wp_terms.name, wp_terms.slug, wp_terms.term_group from wp_terms"

  // Common Table Expression body for the unfiltered term-taxonomy rows
  private def baseTermTaxonomySql: SqlFragment =
    sql"""
         select
             wp_term_taxonomy.term_taxonomy_id, wp_term_taxonomy.term_id,
             wp_terms.name, wp_terms.slug, wp_terms.term_group,
             wp_term_taxonomy.taxonomy, wp_term_taxonomy.description,
             wp_term_taxonomy.parent, wp_term_taxonomy.count
           from wp_term_taxonomy
           join wp_terms on wp_term_taxonomy.term_id = wp_terms.term_id
       """

  // Creates a Common Table Expression for all descendant-or-self term-taxonomy rows of the result of table term_taxo_ids
  private def createDescendantOrSelfPostIdsCte: SqlFragment =
    sql"""
         tt_tree(tt_id, term_id, parent_id) as (
             (select wp_term_taxonomy.term_taxonomy_id, wp_term_taxonomy.term_id, wp_term_taxonomy.parent
               from wp_term_taxonomy
              where wp_term_taxonomy.term_taxonomy_id in (select term_taxonomy_id from term_taxo_ids)) union all
             (select wp_term_taxonomy.term_taxonomy_id, wp_term_taxonomy.term_id, wp_term_taxonomy.parent
                from tt_tree
                join wp_term_taxonomy on tt_tree.tt_id = wp_term_taxonomy.parent)
         )
       """

  private def mapTermRow(rs: ResultSet): TermRow =
    TermRow(id = rs.getLong(1), name = rs.getString(2), slug = rs.getString(3), termGroupOpt = zeroToNone(rs.getLong(4)))

  private def mapTermTaxonomyRow(rs: ResultSet): TermTaxonomyRow =
    TermTaxonomyRow(
      termTaxonomyId = rs.getLong(1),
      termId = rs.getLong(2),
      name = rs.getString(3),
      slug = rs.getString(4),
      termGroupOpt = zeroToNone(rs.getLong(5)),
      taxonomy = rs.getString(6),
      description = rs.getString(7),
      parentOpt = zeroToNone(rs.getLong(8)),
      count = rs.getInt(9)
    )

  private given JdbcDecoder[TermRow] = JdbcDecoder(mapTermRow)

  private given JdbcDecoder[TermTaxonomyRow] = JdbcDecoder(mapTermTaxonomyRow)

  def findAllTerms(): Task[Seq[Term]] =
    for {
      sqlFragment <- ZIO.attempt {
        sql"with terms as ($baseTermSql) select * from terms"
      }
      terms <- transaction {
        selectAll(sqlFragment.as[TermRow]).mapAttempt(_.map(_.toTerm))
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield terms

  def findTerm(termId: Long): Task[Option[Term]] =
    for {
      sqlFragment <- ZIO.attempt {
        sql"with terms as ($baseTermSql) select * from terms where term_id = $termId"
      }
      termOption <- transaction {
        selectOne(sqlFragment.as[TermRow]).mapAttempt(_.map(_.toTerm))
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termOption

  def findTermByName(name: String): Task[Option[Term]] =
    for {
      sqlFragment <- ZIO.attempt {
        sql"with terms as ($baseTermSql) select * from terms where name = $name"
      }
      termOption <- transaction {
        selectOne(sqlFragment.as[TermRow]).mapAttempt(_.map(_.toTerm))
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termOption

  def findAllTermTaxonomies(): Task[Seq[TermTaxonomy]] =
    for {
      sqlFragment <- ZIO.attempt {
        sql"with term_taxos as ($baseTermTaxonomySql) select * from term_taxos"
      }
      termTaxos <- transaction {
        selectAll(sqlFragment.as[TermTaxonomyRow]).mapAttempt(TermTaxonomyRow.toTermTaxonomies)
      }
        .provideEnvironment(ZEnvironment(cp))
    } yield termTaxos

  def findTermTaxonomy(termTaxoId: Long): Task[Option[TermTaxonomy]] =
    val filteredTermTaxonomies: Task[Seq[TermTaxonomy]] =
      for {
        sqlFragment <- ZIO.attempt {
          sql"""
             with recursive
             term_taxo_ids as (
                 select wp_term_taxonomy.term_taxonomy_id from wp_term_taxonomy where wp_term_taxonomy.term_taxonomy_id = $termTaxoId
             ),
             $createDescendantOrSelfPostIdsCte,
             term_taxos as ($baseTermTaxonomySql)
             select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)
           """
        }
        termTaxos <- transaction {
          selectAll(sqlFragment.as[TermTaxonomyRow]).mapAttempt(TermTaxonomyRow.toTermTaxonomies)
        }
          .provideEnvironment(ZEnvironment(cp))
      } yield termTaxos

    // Return top-level term-taxonomies only, be it with their descendants as children, grandchildren etc.
    filteredTermTaxonomies.map(_.find(_.termTaxonomyId == termTaxoId))
  end findTermTaxonomy

  def findTermTaxonomiesByTermId(termId: Long): Task[Seq[TermTaxonomy]] =
    val filteredTermTaxonomies: Task[Seq[TermTaxonomy]] =
      for {
        sqlFragment <- ZIO.attempt {
          sql"""
             with recursive
             term_taxo_ids as (
                 select wp_term_taxonomy.term_taxonomy_id from wp_term_taxonomy where wp_term_taxonomy.term_id = $termId
             ),
             $createDescendantOrSelfPostIdsCte,
             term_taxos as ($baseTermTaxonomySql)
             select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)
           """
        }
        termTaxos <- transaction {
          selectAll(sqlFragment.as[TermTaxonomyRow]).mapAttempt(TermTaxonomyRow.toTermTaxonomies)
        }
          .provideEnvironment(ZEnvironment(cp))
      } yield termTaxos

    // Return top-level term-taxonomies only, be it with their descendants as children, grandchildren etc.
    filteredTermTaxonomies.map(_.filter(_.term.termId == termId))
  end findTermTaxonomiesByTermId

  def findTermTaxonomiesByTermName(termName: String): Task[Seq[TermTaxonomy]] =
    val filteredTermTaxonomies: Task[Seq[TermTaxonomy]] =
      for {
        sqlFragment <- ZIO.attempt {
          sql"""
             with recursive
             term_taxo_ids as (
                 select wp_term_taxonomy.term_taxonomy_id
                   from wp_term_taxonomy
                   join wp_terms on wp_term_taxonomy.term_id = wp_terms.term_id
                  where wp_terms.name = $termName
             ),
             $createDescendantOrSelfPostIdsCte,
             term_taxos as ($baseTermTaxonomySql)
             select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)
           """
        }
        termTaxos <- transaction {
          selectAll(sqlFragment.as[TermTaxonomyRow]).mapAttempt(TermTaxonomyRow.toTermTaxonomies)
        }
          .provideEnvironment(ZEnvironment(cp))
      } yield termTaxos

    // Return top-level term-taxonomies only, be it with their descendants as children, grandchildren etc.
    filteredTermTaxonomies.map(_.filter(_.term.name == termName))
  end findTermTaxonomiesByTermName

  private def zeroToNone(v: Long): Option[Long] = if v == 0 then None else Some(v)

object TermRepoImpl:

  private case class TermRow(id: Long, name: String, slug: String, termGroupOpt: Option[Long]):
    def toTerm: Term = Term(termId = id, name = name, slug = slug, termGroupOption = termGroupOpt)

  private case class TermTaxonomyRow(
      termTaxonomyId: Long,
      termId: Long,
      name: String,
      slug: String,
      termGroupOpt: Option[Long],
      taxonomy: String,
      description: String,
      parentOpt: Option[Long],
      count: Int
  ):
    def toTermTaxonomyWithoutChildren: TermTaxonomy =
      TermTaxonomy(
        termTaxonomyId = termTaxonomyId,
        term = Term(termId, name, slug, termGroupOpt),
        taxonomy = taxonomy,
        description = description,
        children = Seq.empty,
        count = count
      )

  private object TermTaxonomyRow:
    def toTermTaxonomies(rows: Seq[TermTaxonomyRow]): Seq[TermTaxonomy] =
      val termTaxoParents: Seq[(Long, Option[Long])] =
        rows.map { row => row.termTaxonomyId -> row.parentOpt }
      val termTaxoChildren: Map[Long, Seq[Long]] =
        termTaxoParents.collect { case (ttId, Some(parent)) => parent -> ttId }.groupMap(_._1)(_._2)
      val rowsById: Map[Long, TermTaxonomyRow] = rows.map(r => r.termTaxonomyId -> r).toMap

      // Recursive
      def toTermTaxonomy(row: TermTaxonomyRow): TermTaxonomy =
        val children: Seq[TermTaxonomy] =
          termTaxoChildren
            .getOrElse(row.termTaxonomyId, Seq.empty)
            .map(childId => toTermTaxonomy(rowsById(childId)))
        row.toTermTaxonomyWithoutChildren.copy(children = children)

      rows.map(toTermTaxonomy)
    end toTermTaxonomies

  end TermTaxonomyRow

end TermRepoImpl
