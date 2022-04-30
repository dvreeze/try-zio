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
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl.PostRow
import org.jooq.CommonTableExpression
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.Record1
import org.jooq.SQLDialect
import org.jooq.WithStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.`val`
import org.jooq.impl.DSL.cast
import org.jooq.impl.DSL.coalesce
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.inline
import org.jooq.impl.DSL.inlined
import org.jooq.impl.DSL.jsonObjectAgg
import org.jooq.impl.DSL.name
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

  private def makeDsl(): DSLContext = DSL.using(SQLDialect.MYSQL)

  // Common Table Expression for the unfiltered Post rows
  private val basePostCte: CommonTableExpression[_] =
    val dsl = makeDsl()
    import dsl.*
    name("posts").unquotedName
      .as(
        select(
          WP_POSTS.ID,
          WP_POSTS.POST_DATE_GMT,
          WP_POSTS.POST_CONTENT,
          WP_POSTS.POST_TITLE,
          WP_POSTS.POST_EXCERPT,
          WP_POSTS.POST_STATUS,
          WP_POSTS.COMMENT_STATUS,
          WP_POSTS.PING_STATUS,
          WP_POSTS.POST_NAME,
          WP_POSTS.TO_PING,
          WP_POSTS.PINGED,
          WP_POSTS.POST_MODIFIED_GMT,
          WP_POSTS.POST_CONTENT_FILTERED,
          WP_POSTS.POST_PARENT,
          WP_POSTS.GUID,
          WP_POSTS.MENU_ORDER,
          WP_POSTS.POST_TYPE,
          WP_POSTS.POST_MIME_TYPE,
          WP_POSTS.COMMENT_COUNT,
          WP_USERS.ID.as("user_id"),
          WP_USERS.USER_LOGIN,
          WP_USERS.USER_EMAIL,
          WP_USERS.DISPLAY_NAME,
          jsonObjectAgg(
            cast(coalesce(WP_POSTMETA.META_KEY, DSL.inline("")), VARCHAR(255)),
            coalesce(WP_POSTMETA.META_VALUE, DSL.inline(""))
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
    PostRow(
      postId = rs.getLong(1),
      postDate = Try(rs.getTimestamp(2).toInstant).getOrElse(Instant.EPOCH),
      postContentOption = Some(rs.getString(3)),
      postTitle = rs.getString(4),
      postExcerpt = rs.getString(5),
      postStatus = Try(PostStatus.parse(rs.getString(6))).getOrElse(PostStatus.Unknown),
      commentStatus = Try(CommentStatus.parse(rs.getString(7))).getOrElse(CommentStatus.Unknown),
      pingStatus = rs.getString(8),
      postName = rs.getString(9),
      toPing = rs.getString(10),
      pinged = rs.getString(11),
      postModified = Try(rs.getTimestamp(12).toInstant).getOrElse(Instant.EPOCH),
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
      _.select()
        .from(table("posts"))
        .where(field("id", BIGINTUNSIGNED).in(select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
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
      _.select()
        .from(table("posts"))
        .where(field("id", BIGINTUNSIGNED).in(select(field("post_id", BIGINTUNSIGNED)).from(table("post_tree"))))
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
