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

import eu.cdevreeze.tryzio.jdbc.JdbcSupport.*
import eu.cdevreeze.tryzio.jooq.generated.wordpress.Tables.*
import eu.cdevreeze.tryzio.wordpress.model.Term
import eu.cdevreeze.tryzio.wordpress.model.TermTaxonomy
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermRow
import eu.cdevreeze.tryzio.wordpress.repo.TermRepoImpl.TermTaxonomyRow
import org.jooq.CommonTableExpression
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Record1
import org.jooq.SQLDialect
import org.jooq.SelectJoinStep
import org.jooq.WithStep
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.jooq.impl.DSL.`val`
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType.*
import org.jooq.types.ULong
import zio.*

/**
 * Concrete repository of terms and term taxonomies.
 *
 * @author
 *   Chris de Vreeze
 */
final class TermRepoImpl(val conn: Connection) extends TermRepo:

  private def makeDsl(): DSLContext = DSL.using(SQLDialect.MYSQL)

  private val baseTermSql: SelectJoinStep[_] =
    val dsl = makeDsl()
    import dsl.*
    select(WP_TERMS.TERM_ID, WP_TERMS.NAME, WP_TERMS.SLUG, WP_TERMS.TERM_GROUP)
      .from(WP_TERMS)

  // Common Table Expression for the unfiltered term-taxonomy rows
  private val baseTermTaxonomyCte: CommonTableExpression[_] =
    val dsl = makeDsl()
    import dsl.*
    name("term_taxos").unquotedName
      .as(
        select(
          WP_TERM_TAXONOMY.TERM_TAXONOMY_ID,
          WP_TERM_TAXONOMY.TERM_ID,
          WP_TERMS.NAME,
          WP_TERMS.SLUG,
          WP_TERMS.TERM_GROUP,
          WP_TERM_TAXONOMY.TAXONOMY,
          WP_TERM_TAXONOMY.DESCRIPTION,
          WP_TERM_TAXONOMY.PARENT,
          WP_TERM_TAXONOMY.COUNT
        )
          .from(WP_TERM_TAXONOMY)
          .join(WP_TERMS)
          .on(WP_TERM_TAXONOMY.TERM_ID.equal(WP_TERMS.TERM_ID))
      )

  // Creates a Common Table Expression for all descendant-or-self term-taxonomy rows of the result of the given CTE
  // TODO Make term_taxonomy_id column name explicit (probably as method parameter)
  private def createDescendantOrSelfTermTaxoIdsCte(startTermTaxoIdsCte: CommonTableExpression[Record1[ULong]]): CommonTableExpression[_] =
    val dsl = makeDsl()
    import dsl.*
    name("tt_tree").unquotedName
      .fields(name("tt_id").unquotedName, name("term_id").unquotedName, name("parent_id").unquotedName)
      .as(
        select(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID, WP_TERM_TAXONOMY.TERM_ID, WP_TERM_TAXONOMY.PARENT)
          .from(WP_TERM_TAXONOMY)
          .where(
            WP_TERM_TAXONOMY.TERM_TAXONOMY_ID.in(select(field("term_taxonomy_id", BIGINTUNSIGNED)).from(startTermTaxoIdsCte))
          )
          .unionAll(
            select(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID, WP_TERM_TAXONOMY.TERM_ID, WP_TERM_TAXONOMY.PARENT)
              .from(table("tt_tree"))
              .join(WP_TERM_TAXONOMY)
              .on(field("tt_tree.tt_id", BIGINTUNSIGNED).equal(WP_TERM_TAXONOMY.PARENT))
          )
      )

  private def createFullQuery(ctes: Seq[CommonTableExpression[_]], makeQuery: WithStep => Query): Query =
    val dsl = makeDsl()
    import dsl.*
    withRecursive(ctes: _*).pipe(makeQuery)

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
      rows <- using(conn).query(sql.getSQL, Seq.empty)(mapTermRow)
      terms <- ZIO.attempt(rows.map(_.toTerm))
    } yield terms

  def findTerm(termId: Long): Task[Option[Term]] =
    val dsl = makeDsl()
    import dsl.*
    val sql = baseTermSql.where(WP_TERMS.TERM_ID.equal(`val`("dummyTermIdArg", BIGINTUNSIGNED)))
    for {
      rows <- using(conn).query(sql.getSQL, Seq(Argument.LongArg(termId)))(mapTermRow)
      rowOption = rows.headOption
      termOption <- ZIO.attempt(rowOption.map(_.toTerm))
    } yield termOption

  def findTermByName(name: String): Task[Option[Term]] =
    val dsl = makeDsl()
    import dsl.*
    val sql = baseTermSql.where(WP_TERMS.NAME.equal(`val`("dummyNameArg", VARCHAR)))
    for {
      rows <- using(conn).query(sql.getSQL, Seq(Argument.StringArg(name)))(mapTermRow)
      rowOption = rows.headOption
      termOption <- ZIO.attempt(rowOption.map(_.toTerm))
    } yield termOption

  def findAllTermTaxonomies(): Task[Seq[TermTaxonomy]] =
    val dsl = makeDsl()
    import dsl.*
    val sql = createFullQuery(Seq(baseTermTaxonomyCte), _.select().from(table("term_taxos")))
    for {
      rows <- using(conn).query(sql.getSQL, Seq.empty)(mapTermTaxonomyRow)
      termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
    } yield termTaxonomies

  def findTermTaxonomy(termTaxoId: Long): Task[Option[TermTaxonomy]] =
    val dsl = makeDsl()
    import dsl.*
    val startTermTaxoIdCte: CommonTableExpression[Record1[ULong]] =
      name("term_taxo_ids").unquotedName
        .as(
          dsl
            .select(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID)
            .from(WP_TERM_TAXONOMY)
            .where(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID.equal(`val`("dummyTermTaxonomyIdArg", BIGINTUNSIGNED)))
        )
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte(startTermTaxoIdCte)
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      _.select()
        .from(table("term_taxos"))
        .where(field("term_taxonomy_id", BIGINTUNSIGNED).in(select(field("tt_id", BIGINTUNSIGNED)).from(table("tt_tree"))))
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.LongArg(termTaxoId)))(mapTermTaxonomyRow)
        termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
      } yield termTaxonomies

    filteredTermTaxonomies.map(_.find(_.termTaxonomyId == termTaxoId))
  end findTermTaxonomy

  def findTermTaxonomiesByTermId(termId: Long): Task[Seq[TermTaxonomy]] =
    val dsl = makeDsl()
    import dsl.*
    val startTermTaxoIdCte: CommonTableExpression[Record1[ULong]] =
      name("term_taxo_ids").unquotedName
        .as(
          select(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID)
            .from(WP_TERM_TAXONOMY)
            .where(
              field(
                WP_TERM_TAXONOMY.TERM_ID.in(
                  select(WP_TERMS.TERM_ID)
                    .from(WP_TERMS)
                    .where(WP_TERMS.TERM_ID.equal(`val`("dummyTermIdArg", BIGINTUNSIGNED)))
                )
              )
            )
        )
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte(startTermTaxoIdCte)
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      _.select()
        .from(table("term_taxos"))
        .where(field("term_taxonomy_id", BIGINTUNSIGNED).in(select(field("tt_id", BIGINTUNSIGNED)).from(table("tt_tree"))))
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.LongArg(termId)))(mapTermTaxonomyRow)
        termTaxonomies <- ZIO.attempt(TermTaxonomyRow.toTermTaxonomies(rows))
      } yield termTaxonomies

    filteredTermTaxonomies.map(_.filter(_.term.termId == termId))
  end findTermTaxonomiesByTermId

  def findTermTaxonomiesByTermName(termName: String): Task[Seq[TermTaxonomy]] =
    val dsl = makeDsl()
    import dsl.*
    val startTermTaxoIdCte: CommonTableExpression[Record1[ULong]] =
      name("term_taxo_ids").unquotedName
        .as(
          select(WP_TERM_TAXONOMY.TERM_TAXONOMY_ID)
            .from(WP_TERM_TAXONOMY)
            .where(
              field(
                WP_TERM_TAXONOMY.TERM_ID.in(
                  select(WP_TERMS.TERM_ID)
                    .from(WP_TERMS)
                    .where(WP_TERMS.NAME.equal(`val`("dummyNameArg", VARCHAR)))
                )
              )
            )
        )
    val recursiveTermTaxoIdsCte = createDescendantOrSelfTermTaxoIdsCte(startTermTaxoIdCte)
    val sql = createFullQuery(
      Seq(startTermTaxoIdCte, recursiveTermTaxoIdsCte, baseTermTaxonomyCte),
      _.select()
        .from(table("term_taxos"))
        .where(field("term_taxonomy_id", BIGINTUNSIGNED).in(select(field("tt_id", BIGINTUNSIGNED)).from(table("tt_tree"))))
    )

    val filteredTermTaxonomies =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.StringArg(termName)))(mapTermTaxonomyRow)
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
