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
import java.time.Instant

import scala.util.Try
import scala.util.chaining.*

import eu.cdevreeze.tryzio.jdbc.JdbcSupport.*
import eu.cdevreeze.tryzio.wordpress.model.CommentStatus
import eu.cdevreeze.tryzio.wordpress.model.Post
import eu.cdevreeze.tryzio.wordpress.model.PostStatus
import eu.cdevreeze.tryzio.wordpress.model.PostType
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl.PostRow
import org.jooq.CommonTableExpression
import org.jooq.Query
import org.jooq.Record1
import org.jooq.SQLDialect
import org.jooq.WithStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.jsonObjectAgg
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType.*
import org.jooq.types.ULong
import zio.*
import zio.json.*

/**
 * Concrete repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostRepoImpl(val conn: Connection) extends PostRepo:

  // Common Table Expression for the unfiltered Post rows
  private val basePostCte: CommonTableExpression[_] =
    DSL
      .name(DSL.name("posts").unquotedName)
      .as(
        DSL
          .select(
            field("p.id", BIGINTUNSIGNED),
            field("p.post_date_gmt", OFFSETDATETIME),
            field("p.post_content", CLOB),
            field("p.post_title", CLOB),
            field("p.post_excerpt", CLOB),
            field("p.post_status", VARCHAR),
            field("p.comment_status", VARCHAR),
            field("p.ping_status", VARCHAR),
            field("p.post_name", VARCHAR),
            field("p.to_ping", CLOB),
            field("p.pinged", CLOB),
            field("p.post_modified_gmt", OFFSETDATETIME),
            field("p.post_content_filtered", CLOB),
            field("p.post_parent", BIGINTUNSIGNED),
            field("p.guid", VARCHAR),
            field("p.menu_order", INTEGER),
            field("p.post_type", VARCHAR),
            field("p.post_mime_type", VARCHAR),
            field("p.comment_count", BIGINT),
            field("u.id", BIGINTUNSIGNED).as("user_id"),
            field("u.user_login", VARCHAR),
            field("u.user_email", VARCHAR),
            field("u.display_name", VARCHAR),
            DSL.inlined(field("""json_objectagg(coalesce(pm.meta_key, ""), coalesce(pm.meta_value, ""))""", JSON))
          )
          .from(table("wp_posts p"))
          .leftJoin(table("wp_users u"))
          .on(field("p.post_author", BIGINTUNSIGNED).equal(field("u.id", BIGINTUNSIGNED)))
          .leftJoin(table("wp_postmeta pm"))
          .on(field("p.id", BIGINTUNSIGNED).equal(field("pm.post_id", BIGINTUNSIGNED)))
          .groupBy(field("p.id", BIGINTUNSIGNED))
      )

  // Creates a Common Table Expression for all descendant-or-self Post rows of the result of the given CTE
  // TODO Make ID column name explicit (probably as method parameter)
  private def createDescendantOrSelfPostIdsCte(startPostIdsCte: CommonTableExpression[Record1[ULong]]): CommonTableExpression[_] =
    DSL
      .name(DSL.name("post_tree").unquotedName)
      .fields(DSL.name("post_id").unquotedName, DSL.name("post_name").unquotedName, DSL.name("parent_id").unquotedName)
      .as(
        DSL
          .select(field("id", BIGINTUNSIGNED), field("post_name", VARCHAR), field("post_parent", BIGINTUNSIGNED))
          .from(table("wp_posts"))
          .where(field("id", BIGINTUNSIGNED).in(DSL.select(field("id", BIGINTUNSIGNED)).from(startPostIdsCte)))
          .unionAll(
            DSL
              .select(field("p.id", BIGINTUNSIGNED), field("p.post_name", VARCHAR), field("p.post_parent", BIGINTUNSIGNED))
              .from(table("post_tree"))
              .join(table("wp_posts p"))
              .on(field("post_tree.post_id", BIGINTUNSIGNED).equal(field("p.post_parent", BIGINTUNSIGNED)))
          )
      )

  private def createFullQuery(ctes: Seq[CommonTableExpression[_]], makeQuery: WithStep => Query): Query =
    DSL.withRecursive(ctes: _*).pipe(makeQuery)

  private def mapPostRow(rs: ResultSet, idx: Int): PostRow =
    PostRow(
      postId = rs.getLong(1),
      postDate = rs.getTimestamp(2).toInstant,
      postContentOption = Some(rs.getString(3)),
      postTitle = rs.getString(4),
      postExcerpt = rs.getString(5),
      postStatus = Try(PostStatus.parse(rs.getString(6))).getOrElse(PostStatus.Unknown),
      commentStatus = Try(CommentStatus.parse(rs.getString(7))).getOrElse(CommentStatus.Unknown),
      pingStatus = rs.getString(8),
      postName = rs.getString(9),
      toPing = rs.getString(10),
      pinged = rs.getString(11),
      postModified = rs.getTimestamp(12).toInstant,
      postContentFilteredOption = Some(rs.getString(13)),
      parentOpt = zeroToNone(rs.getLong(14)),
      guid = rs.getString(15),
      menuOrder = rs.getInt(16),
      postType = Try(PostType.parse(rs.getString(17))).getOrElse(PostType.Unknown),
      postMimeType = rs.getString(18),
      commentCount = rs.getInt(19),
      userIdOpt = zeroToNone(rs.getLong(20)),
      userLoginOpt = emptyToNone(rs.getString(21)),
      userEmailOpt = emptyToNone(rs.getString(22)),
      userDisplayNameOpt = emptyToNone(rs.getString(23)),
      postMeta = JsonDecoder[Map[String, String]].decodeJson(rs.getString(24)).getOrElse(Map.empty)
    )

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    val sql = createFullQuery(Seq(basePostCte), _.select().from(table("posts")))
    for {
      rows <- using(conn).query(sql.getSQL, Seq.empty)(mapPostRow)
      posts <- ZIO.attempt(PostRow.toPosts(rows))
      filteredPosts <- ZIO.filter(posts)(p)
    } yield filteredPosts

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    filterPosts(p).map(_.map(_.copy(postContentOption = None).copy(postContentFilteredOption = None)))

  def findPost(postId: Long): Task[Option[Post]] =
    val startPostIdCte: CommonTableExpression[Record1[ULong]] =
      DSL
        .name(DSL.name("post_ids").unquotedName)
        .as(
          DSL
            .select(field("id", BIGINTUNSIGNED))
            .from(table("wp_posts"))
            .where(field("id", BIGINTUNSIGNED).equal(DSL.`val`("dummyIdArg", BIGINTUNSIGNED)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select()
        .from(table("posts"))
        .where(field("id", BIGINTUNSIGNED).in(DSL.select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
    )

    val filteredPosts =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.LongArg(postId)))(mapPostRow)
        posts <- ZIO.attempt(PostRow.toPosts(rows))
      } yield posts

    filteredPosts.map(_.find(_.postId == postId))
  end findPost

  def findPostByName(name: String): Task[Option[Post]] =
    val startPostIdCte: CommonTableExpression[Record1[ULong]] =
      DSL
        .name(DSL.name("post_ids").unquotedName)
        .as(
          DSL
            .select(field("id", BIGINTUNSIGNED))
            .from(table("wp_posts"))
            .where(field("post_name", VARCHAR).equal(DSL.`val`("dummyNameArg", VARCHAR)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select()
        .from(table("posts"))
        .where(field("id", BIGINTUNSIGNED).in(DSL.select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
    )

    val filteredPosts =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.StringArg(name)))(mapPostRow)
        posts <- ZIO.attempt(PostRow.toPosts(rows))
      } yield posts

    filteredPosts.map(_.find(_.postName == name))
  end findPostByName

  private def zeroToNone(v: Long): Option[Long] = if v == 0 then None else Some(v)
  private def emptyToNone(v: String): Option[String] = if v.isEmpty then None else Some(v)

object PostRepoImpl:

  private case class PostRow(
      postId: Long,
      postDate: Instant,
      postContentOption: Option[String],
      postTitle: String,
      postExcerpt: String,
      postStatus: PostStatus,
      commentStatus: CommentStatus,
      pingStatus: String,
      postName: String,
      toPing: String,
      pinged: String,
      postModified: Instant,
      postContentFilteredOption: Option[String],
      parentOpt: Option[Long],
      guid: String,
      menuOrder: Int,
      postType: PostType,
      postMimeType: String,
      commentCount: Int,
      userIdOpt: Option[Long],
      userLoginOpt: Option[String],
      userEmailOpt: Option[String],
      userDisplayNameOpt: Option[String],
      postMeta: Map[String, String]
  ):
    def toPostWithoutChildren: Post =
      Post(
        postId = postId,
        postAuthorOption = userIdOpt.map { userId =>
          Post.User(userId, userLoginOpt.getOrElse(""), userEmailOpt.getOrElse(""), userDisplayNameOpt.getOrElse(""))
        },
        postDate = postDate,
        postContentOption = postContentOption,
        postTitle = postTitle,
        postExcerpt = postExcerpt,
        postStatus = postStatus,
        commentStatus = commentStatus,
        pingStatus = pingStatus,
        postName = postName,
        toPing = toPing,
        pinged = pinged,
        postModified = postModified,
        postContentFilteredOption = postContentFilteredOption,
        children = Seq.empty,
        guid = guid,
        menuOrder = menuOrder,
        postType = postType,
        postMimeType = postMimeType,
        commentCount = commentCount,
        metaData = postMeta.filter { (k, v) => k.nonEmpty && v.nonEmpty }
      )

  private object PostRow:
    def toPosts(rows: Seq[PostRow]): Seq[Post] =
      val postParents: Seq[(Long, Option[Long])] =
        rows.map { row => row.postId -> row.parentOpt }
      val postChildren: Map[Long, Seq[Long]] =
        postParents.collect { case (postId, Some(parent)) => parent -> postId }.groupMap(_._1)(_._2)
      val rowsById: Map[Long, PostRow] = rows.map(r => r.postId -> r).toMap

      // Recursive
      def toPost(row: PostRow): Post =
        val children: Seq[Post] =
          postChildren
            .getOrElse(row.postId, Seq.empty)
            .map(childId => toPost(rowsById(childId)))
        row.toPostWithoutChildren.copy(children = children)

      rows.map(toPost)
    end toPosts

  end PostRow

end PostRepoImpl
