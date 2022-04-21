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

import eu.cdevreeze.tryzio.jdbc.JdbcSupport.*
import eu.cdevreeze.tryzio.wordpress.model.CommentStatus
import eu.cdevreeze.tryzio.wordpress.model.Post
import eu.cdevreeze.tryzio.wordpress.model.PostStatus
import eu.cdevreeze.tryzio.wordpress.model.PostType
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl.PostRow
import zio.*
import zio.json.*

/**
 * Concrete repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostRepoImpl(val conn: Connection) extends PostRepo:

  private val basePostSql =
    """
      |select
      |    p.id,
      |    p.post_date_gmt,
      |    p.post_content,
      |    p.post_title,
      |    p.post_excerpt,
      |    p.post_status,
      |    p.comment_status,
      |    p.ping_status,
      |    p.post_name,
      |    p.to_ping,
      |    p.pinged,
      |    p.post_modified_gmt,
      |    p.post_content_filtered,
      |    p.post_parent,
      |    p.guid,
      |    p.menu_order,
      |    p.post_type,
      |    p.post_mime_type,
      |    p.comment_count,
      |    u.id,
      |    u.user_login,
      |    u.user_email,
      |    u.display_name,
      |    JSON_OBJECTAGG(pm.meta_key, pm.meta_value)
      |  from wp_posts p
      |  left join wp_users u on (p.post_author = u.id)
      |  left join wp_postmeta pm on (p.id = pm.post_id)
      | where pm.meta_key is not null
      |   and pm.meta_value is not null
      | group by p.id
      |""".stripMargin

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
      postMetaJsonString = rs.getString(24)
    )

  def filterPosts(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    val sql = basePostSql
    for {
      rows <- using(conn).query(sql, Seq.empty)(mapPostRow)
      posts <- ZIO.attempt(PostRow.toPosts(rows))
      filteredPosts <- ZIO.filter(posts)(p)
    } yield filteredPosts

  def filterPostsReturningNoContent(p: Post => Task[Boolean]): Task[Seq[Post]] =
    // Inefficient
    filterPosts(p).map(_.map(_.copy(postContentOption = None).copy(postContentFilteredOption = None)))

  def findPost(postId: Long): Task[Option[Post]] =
    // Very inefficient
    filterPosts(_ => IO.succeed(true)).map(_.find(_.postId == postId))

  def findPostByName(name: String): Task[Option[Post]] =
    // Very inefficient
    filterPosts(_ => IO.succeed(true)).map(_.find(_.postName == name))

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
      postMetaJsonString: String
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
        metaData = JsonDecoder[Map[String, String]].decodeJson(postMetaJsonString).getOrElse(Map.empty)
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
