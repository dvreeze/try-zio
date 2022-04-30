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
import eu.cdevreeze.tryzio.jooq.generated.wordpress.Tables.*
import eu.cdevreeze.tryzio.wordpress.model.CommentStatus
import eu.cdevreeze.tryzio.wordpress.model.Post
import eu.cdevreeze.tryzio.wordpress.model.PostStatus
import eu.cdevreeze.tryzio.wordpress.model.PostType
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2.PostRow
import org.jooq.CommonTableExpression
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Record1
import org.jooq.SQLDialect
import org.jooq.WithStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.`val`
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.inlined
import org.jooq.impl.DSL.name
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

  private def makeDsl(): DSLContext = DSL.using(SQLDialect.MYSQL)

  private val dateFormat = "%Y-%m-%dT%H:%i:%sZ"

  // Common Table Expression for the unfiltered Post rows
  private val basePostCte: CommonTableExpression[_] =
    val dsl = makeDsl()
    import dsl.*
    name("posts").unquotedName
      .fields(name("post_id").unquotedName, name("json_result").unquotedName)
      .as(
        select(
          WP_POSTS.ID.as("post_id"),
          inlined(
            field(
              s"""
               |JSON_OBJECT(
               |  "postId", wp_posts.id,
               |  "postDate", DATE_FORMAT(IF(YEAR(post_date_gmt) = 0, TIMESTAMP('1970-01-01 00:00:00'), post_date_gmt), '$dateFormat'),
               |  "postContentOption", post_content,
               |  "postTitle", post_title,
               |  "postExcerpt", post_excerpt,
               |  "postStatus", post_status,
               |  "commentStatus", comment_status,
               |  "pingStatus", ping_status,
               |  "postName", post_name,
               |  "toPing", to_ping,
               |  "pinged", pinged,
               |  "postModified", DATE_FORMAT(IF(YEAR(post_modified_gmt) = 0, TIMESTAMP('1970-01-01 00:00:00'), post_modified_gmt), '$dateFormat'),
               |  "postContentFilteredOption", post_content_filtered,
               |  "parentOpt", IF(post_parent = 0, null, post_parent),
               |  "guid", guid,
               |  "menuOrder", menu_order,
               |  "postType", post_type,
               |  "postMimeType", post_mime_type,
               |  "commentCount", comment_count,
               |  "postAuthorOption", IF(post_author = 0, null, JSON_OBJECT(
               |      "userId", wp_users.id,
               |      "userLogin", user_login,
               |      "userEmail", user_email,
               |      "displayName", display_name
               |    )
               |  ),
               |  "postMeta", JSON_OBJECTAGG(COALESCE(meta_key, ""), COALESCE(meta_value, ""))
               |)
               |""".stripMargin.trim,
              JSON
            )
          )
        )
          .from(WP_POSTS)
          .leftJoin(WP_USERS)
          .on(WP_POSTS.POST_AUTHOR.equal(WP_USERS.ID))
          .leftJoin(WP_POSTMETA)
          .on(WP_POSTS.ID.equal(WP_POSTMETA.POST_ID))
          .groupBy(WP_POSTS.ID)
      )

  // Creates a Common Table Expression for all descendant-or-self Post rows of the result of the given CTE
  // TODO Make ID column name explicit (probably as method parameter)
  private def createDescendantOrSelfPostIdsCte(startPostIdsCte: CommonTableExpression[Record1[ULong]]): CommonTableExpression[_] =
    val dsl = makeDsl()
    import dsl.*
    name("post_tree").unquotedName
      .fields(name("post_id").unquotedName, name("post_name").unquotedName, name("parent_id").unquotedName)
      .as(
        select(WP_POSTS.ID, WP_POSTS.POST_NAME, WP_POSTS.POST_PARENT)
          .from(WP_POSTS)
          .where(WP_POSTS.ID.in(select(field("id", BIGINTUNSIGNED)).from(startPostIdsCte)))
          .unionAll(
            select(WP_POSTS.ID, WP_POSTS.POST_NAME, WP_POSTS.POST_PARENT)
              .from(table("post_tree"))
              .join(WP_POSTS)
              .on(field("post_tree.post_id", BIGINTUNSIGNED).equal(WP_POSTS.POST_PARENT))
          )
      )

  private def createFullQuery(ctes: Seq[CommonTableExpression[_]], makeQuery: WithStep => Query): Query =
    val dsl = makeDsl()
    import dsl.*
    withRecursive(ctes: _*).pipe(makeQuery)

  private def mapPostRow(rs: ResultSet, idx: Int): PostRow =
    val postId: Long = rs.getLong(1)
    JsonDecoder[PostRow]
      .decodeJson(rs.getString(2))
      .fold(sys.error, identity)
      .ensuring(_.postId == postId)

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]] =
    val dsl = makeDsl()
    import dsl.*
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
    val dsl = makeDsl()
    import dsl.*
    val startPostIdCte: CommonTableExpression[Record1[ULong]] =
      name("post_ids").unquotedName
        .as(
          select(WP_POSTS.ID)
            .from(WP_POSTS)
            .where(WP_POSTS.ID.equal(`val`("dummyIdArg", BIGINTUNSIGNED)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select(field("post_id", BIGINTUNSIGNED), field("json_result", JSON))
        .from(table("posts"))
        .where(field("post_id", BIGINTUNSIGNED).in(select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
    )

    val filteredPosts =
      for {
        rows <- using(conn).query(sql.getSQL, Seq(Argument.LongArg(postId)))(mapPostRow)
        posts <- ZIO.attempt(PostRow.toPosts(rows))
      } yield posts

    filteredPosts.map(_.find(_.postId == postId))
  end findPost

  def findPostByName(name: String): Task[Option[Post]] =
    val dsl = makeDsl()
    import dsl.*
    val startPostIdCte: CommonTableExpression[Record1[ULong]] =
      DSL
        .name("post_ids")
        .unquotedName
        .as(
          select(WP_POSTS.ID)
            .from(WP_POSTS)
            .where(WP_POSTS.POST_NAME.equal(`val`("dummyNameArg", VARCHAR)))
        )
    val recursivePostIdsCte = createDescendantOrSelfPostIdsCte(startPostIdCte)
    val sql = createFullQuery(
      Seq(startPostIdCte, recursivePostIdsCte, basePostCte),
      _.select(field("post_id", BIGINTUNSIGNED), field("json_result", JSON))
        .from(table("posts"))
        .where(field("post_id", BIGINTUNSIGNED).in(select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
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
