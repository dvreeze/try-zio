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
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2.PostRow
import org.jooq.CommonTableExpression
import org.jooq.Query
import org.jooq.Record1
import org.jooq.WithStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType.*
import org.jooq.types.ULong
import zio.*
import zio.json.*

/**
 * Alternative concrete repository of posts, exploiting JSON "rows" as query results.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostRepoImpl2(val conn: Connection) extends PostRepo:

  private val dateFormat = "%Y-%m-%dT%H:%i:%sZ"

  // Common Table Expression for the unfiltered Post rows
  private val basePostCte: CommonTableExpression[_] =
    DSL
      .name("posts")
      .unquotedName
      .fields(DSL.name("post_id").unquotedName, DSL.name("json_result").unquotedName)
      .as(
        DSL
          .select(
            field("p.id", BIGINTUNSIGNED).as("post_id"),
            DSL.inlined(
              field(
                s"""
               |JSON_OBJECT(
               |  "postId", p.id,
               |  "postDate", DATE_FORMAT(p.post_date_gmt, '$dateFormat'),
               |  "postContentOption", p.post_content,
               |  "postTitle", p.post_title,
               |  "postExcerpt", p.post_excerpt,
               |  "postStatus", p.post_status,
               |  "commentStatus", p.comment_status,
               |  "pingStatus", p.ping_status,
               |  "postName", p.post_name,
               |  "toPing", p.to_ping,
               |  "pinged", p.pinged,
               |  "postModified", DATE_FORMAT(p.post_modified_gmt, '$dateFormat'),
               |  "postContentFilteredOption", p.post_content_filtered,
               |  "parentOpt", IF(p.post_parent = 0, null, p.post_parent),
               |  "guid", p.guid,
               |  "menuOrder", p.menu_order,
               |  "postType", p.post_type,
               |  "postMimeType", p.post_mime_type,
               |  "commentCount", p.comment_count,
               |  "postAuthorOption", IF(p.post_author = 0, null, JSON_OBJECT(
               |      "userId", u.id,
               |      "userLogin", u.user_login,
               |      "userEmail", u.user_email,
               |      "displayName", u.display_name
               |    )
               |  ),
               |  "postMeta", JSON_OBJECTAGG(COALESCE(pm.meta_key, ""), COALESCE(pm.meta_value, ""))
               |)
               |""".stripMargin.trim,
                JSON
              )
            )
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
      .name("post_tree")
      .unquotedName
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
    val postId: Long = rs.getLong(1)
    JsonDecoder[PostRow]
      .decodeJson(rs.getString(2))
      .fold(sys.error, identity)
      .ensuring(_.postId == postId)

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    val sql = createFullQuery(
      Seq(basePostCte),
      _.select(field("post_id", BIGINTUNSIGNED), field("json_result", JSON)).from(table("posts"))
    )
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
        .name("post_ids")
        .unquotedName
        .as(
          DSL
            .select(field("id", BIGINTUNSIGNED))
            .from(table("wp_posts"))
            .where(field("id", BIGINTUNSIGNED).equal(DSL.`val`("dummyIdArg", BIGINTUNSIGNED)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select(field("post_id", BIGINTUNSIGNED), field("json_result", JSON))
        .from(table("posts"))
        .where(field("post_id", BIGINTUNSIGNED).in(DSL.select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
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
        .name("post_ids")
        .unquotedName
        .as(
          DSL
            .select(field("id", BIGINTUNSIGNED))
            .from(table("wp_posts"))
            .where(field("post_name", VARCHAR).equal(DSL.`val`("dummyNameArg", VARCHAR)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select(field("post_id", BIGINTUNSIGNED), field("json_result", JSON))
        .from(table("posts"))
        .where(field("post_id", BIGINTUNSIGNED).in(DSL.select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
    )

    val filteredPosts =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.StringArg(name)))(mapPostRow)
        posts <- ZIO.attempt(PostRow.toPosts(rows))
      } yield posts

    filteredPosts.map(_.find(_.postName == name))
  end findPostByName

object PostRepoImpl2:

  // PostRow knows its parent, if any, whereas Post contains a children property
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
      postAuthorOption: Option[Post.User],
      postMeta: Map[String, String]
  ):
    def toPostWithoutChildren: Post =
      Post(
        postId = postId,
        postAuthorOption = postAuthorOption,
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

    given decoder: JsonDecoder[PostRow] = DeriveJsonDecoder.gen[PostRow]
    given encoder: JsonEncoder[PostRow] = DeriveJsonEncoder.gen[PostRow]

  end PostRow

end PostRepoImpl2
