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
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2.MainPostData
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2.PostRow
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

  private val basePostSql =
    s"""
      |select
      |    JSON_OBJECT(
      |      "postId", p.id,
      |      "postDate", DATE_FORMAT(p.post_date_gmt, '$dateFormat'),
      |      "postContentOption", p.post_content,
      |      "postTitle", p.post_title,
      |      "postExcerpt", p.post_excerpt,
      |      "postStatus", p.post_status,
      |      "commentStatus", p.comment_status,
      |      "pingStatus", p.ping_status,
      |      "postName", p.post_name,
      |      "toPing", p.to_ping,
      |      "pinged", p.pinged,
      |      "postModified", DATE_FORMAT(p.post_modified_gmt, '$dateFormat'),
      |      "postContentFilteredOption", p.post_content_filtered,
      |      "parentOpt", IF(p.post_parent = 0, null, p.post_parent),
      |      "guid", p.guid,
      |      "menuOrder", p.menu_order,
      |      "postType", p.post_type,
      |      "postMimeType", p.post_mime_type,
      |      "commentCount", p.comment_count
      |    ),
      |    u.id,
      |    u.user_login,
      |    u.user_email,
      |    u.display_name,
      |    JSON_OBJECTAGG(COALESCE(pm.meta_key, ""), COALESCE(pm.meta_value, ""))
      |  from wp_posts p
      |  left join wp_users u on (p.post_author = u.id)
      |  left join wp_postmeta pm on (p.id = pm.post_id)
      | group by p.id
      |""".stripMargin

  private def mapPostRow(rs: ResultSet, idx: Int): PostRow =
    PostRow(
      mainPostData = JsonDecoder[MainPostData].decodeJson(rs.getString(1)).fold(sys.error, identity),
      userIdOpt = zeroToNone(rs.getLong(2)),
      userLoginOpt = emptyToNone(rs.getString(3)),
      userEmailOpt = emptyToNone(rs.getString(4)),
      userDisplayNameOpt = emptyToNone(rs.getString(5)),
      postMeta = JsonDecoder[Map[String, String]].decodeJson(rs.getString(6)).getOrElse(Map.empty)
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

object PostRepoImpl2:

  private case class MainPostData(
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
      commentCount: Int
  )

  private given decoder: JsonDecoder[MainPostData] = DeriveJsonDecoder.gen[MainPostData]
  private given encoder: JsonEncoder[MainPostData] = DeriveJsonEncoder.gen[MainPostData]

  private case class PostRow(
      mainPostData: MainPostData,
      userIdOpt: Option[Long],
      userLoginOpt: Option[String],
      userEmailOpt: Option[String],
      userDisplayNameOpt: Option[String],
      postMeta: Map[String, String]
  ):
    def toPostWithoutChildren: Post =
      Post(
        postId = mainPostData.postId,
        postAuthorOption = userIdOpt.map { userId =>
          Post.User(userId, userLoginOpt.getOrElse(""), userEmailOpt.getOrElse(""), userDisplayNameOpt.getOrElse(""))
        },
        postDate = mainPostData.postDate,
        postContentOption = mainPostData.postContentOption,
        postTitle = mainPostData.postTitle,
        postExcerpt = mainPostData.postExcerpt,
        postStatus = mainPostData.postStatus,
        commentStatus = mainPostData.commentStatus,
        pingStatus = mainPostData.pingStatus,
        postName = mainPostData.postName,
        toPing = mainPostData.toPing,
        pinged = mainPostData.pinged,
        postModified = mainPostData.postModified,
        postContentFilteredOption = mainPostData.postContentFilteredOption,
        children = Seq.empty,
        guid = mainPostData.guid,
        menuOrder = mainPostData.menuOrder,
        postType = mainPostData.postType,
        postMimeType = mainPostData.postMimeType,
        commentCount = mainPostData.commentCount,
        metaData = postMeta.filter { (k, v) => k.nonEmpty && v.nonEmpty }
      )

  private object PostRow:
    def toPosts(rows: Seq[PostRow]): Seq[Post] =
      val postParents: Seq[(Long, Option[Long])] =
        rows.map { row => row.mainPostData.postId -> row.mainPostData.parentOpt }
      val postChildren: Map[Long, Seq[Long]] =
        postParents.collect { case (postId, Some(parent)) => parent -> postId }.groupMap(_._1)(_._2)
      val rowsById: Map[Long, PostRow] = rows.map(r => r.mainPostData.postId -> r).toMap

      // Recursive
      def toPost(row: PostRow): Post =
        val children: Seq[Post] =
          postChildren
            .getOrElse(row.mainPostData.postId, Seq.empty)
            .map(childId => toPost(rowsById(childId)))
        row.toPostWithoutChildren.copy(children = children)

      rows.map(toPost)
    end toPosts

  end PostRow

end PostRepoImpl2
