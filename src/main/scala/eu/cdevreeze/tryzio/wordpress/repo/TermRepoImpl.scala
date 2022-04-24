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

import eu.cdevreeze.tryzio.jdbc.JdbcSupport.*
import eu.cdevreeze.tryzio.wordpress.model.Term
import eu.cdevreeze.tryzio.wordpress.model.TermTaxonomy
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermRow
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermTaxonomyRow
import zio.*

/**
 * Concrete repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
final class TermRepoImpl(val conn: Connection) extends TermRepo:

  private val baseTermSql = "select term_id, name, slug, term_group from wp_terms"

  // Common Table Expression for the unfiltered term-taxonomy rows
  private val baseTermTaxonomyCte =
    """
      |term_taxos as
      |(
      |select
      |    tt.term_taxonomy_id,
      |    tt.term_id,
      |    t.name,
      |    t.slug,
      |    t.term_group,
      |    tt.taxonomy,
      |    tt.description,
      |    tt.parent,
      |    tt.count
      |  from wp_term_taxonomy tt
      |  join wp_terms t on tt.term_id = t.term_id
      |)
      |""".stripMargin

  // Creates a Common Table Expression for all descendant-or-self term-taxonomy rows of the result of the given CTE
  private def createDescendantOrSelfTermTaxoIdsCte(startTermTaxoIdsCteName: String): String =
    s"""
      |tt_tree (tt_id, term_id, parent_id) as
      |(
      |  (select term_taxonomy_id, term_id, parent from wp_term_taxonomy where term_taxonomy_id in (select * from $startTermTaxoIdsCteName))
      |  union all
      |  (select tt.term_taxonomy_id, tt.term_id, tt.parent from tt_tree join wp_term_taxonomy tt on tt_tree.tt_id = tt.parent)
      |)
      |""".stripMargin

  private def createFullQuery(ctes: Seq[String], query: String): String =
    s"""
       |with recursive
       |${ctes.mkString(",\n")}
       |$query
       |""".stripMargin.trim.replace("\n\n", "\n")

  private def mapTermRow(rs: ResultSet, idx: Int): TermRow =
    TermRow(id = rs.getLong(1), name = rs.getString(2), slug = rs.getString(3), termGroupOpt = zeroToNone(rs.getLong(4)))

  private def mapTermTaxonomyRow(rs: ResultSet, idx: Int): TermTaxonomyRow =
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

  def findAllTerms(): Task[Seq[Term]] =
    val sql = baseTermSql
    for {
      rows <- using(conn).query(sql, Seq.empty)(mapTermRow)
      terms <- ZIO.attempt(rows.map(_.toTerm))
    } yield terms

  def findTerm(termId: Long): Task[Option[Term]] =
    val sql = s"$baseTermSql where term_id = ?"
    for {
      rows <- using(conn).query(sql, Seq(Argument.LongArg(termId)))(mapTermRow)
      rowOption = rows.headOption
      termOption <- ZIO.attempt(rowOption.map(_.toTerm))
    } yield termOption

  def findTermByName(name: String): Task[Option[Term]] =
    val sql = s"$baseTermSql where name = ?"
    for {
      rows <- using(conn).query(sql, Seq(Argument.StringArg(name)))(mapTermRow)
      rowOption = rows.headOption
      termOption <- ZIO.attempt(rowOption.map(_.toTerm))
    } yield termOption

  def findAllTermTaxonomies(): Task[Seq[TermTaxonomy]] =
    val sql = createFullQuery(Seq(baseTermTaxonomyCte), "select * from term_taxos")
    for {
      rows <- using(conn).query(sql, Seq.empty)(mapTermTaxonomyRow)
      termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
    } yield termTaxonomies

  def findTermTaxonomy(termTaxoId: Long): Task[Option[TermTaxonomy]] =
    val startTermTaxoIdCte =
      "term_taxo_ids as (select term_taxonomy_id from wp_term_taxonomy where term_taxonomy_id = ?)"
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte("term_taxo_ids")
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      "select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)"
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql, Seq(Argument.LongArg(termTaxoId)))(mapTermTaxonomyRow)
        termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
      } yield termTaxonomies

    filteredTermTaxonomies.map(_.find(_.termTaxonomyId == termTaxoId))
  end findTermTaxonomy

  def findTermTaxonomiesByTermId(termId: Long): Task[Seq[TermTaxonomy]] =
    val startTermTaxoIdCte =
      """
        |term_taxo_ids as
        |(select term_taxonomy_id
        |from wp_term_taxonomy
        |where term_id in (select t.term_id from wp_terms t where t.term_id = ?))
        |""".stripMargin
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte("term_taxo_ids")
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      "select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)"
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql, Seq(Argument.LongArg(termId)))(mapTermTaxonomyRow)
        termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
      } yield termTaxonomies

    filteredTermTaxonomies.map(_.filter(_.term.termId == termId))
  end findTermTaxonomiesByTermId

  def findTermTaxonomiesByTermName(termName: String): Task[Seq[TermTaxonomy]] =
    val startTermTaxoIdCte =
      """
        |term_taxo_ids as
        |(select term_taxonomy_id
        |from wp_term_taxonomy
        |where term_id in (select t.term_id from wp_terms t where t.name = ?))
        |""".stripMargin
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte("term_taxo_ids")
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      "select * from term_taxos where term_taxonomy_id in (select tt_id from tt_tree)"
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql, Seq(Argument.StringArg(termName)))(mapTermTaxonomyRow)
        termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
      } yield termTaxonomies

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
