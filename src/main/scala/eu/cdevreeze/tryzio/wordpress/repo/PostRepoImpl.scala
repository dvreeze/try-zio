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
import scala.util.Using
import scala.util.chaining.*

import eu.cdevreeze.tryzio.wordpress.model.*
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl.PostRow
import zio.*
import zio.jdbc.*
import zio.json.*

/**
 * Concrete repository of posts.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostRepoImpl() extends PostRepo:

  // Common Table Expression body for the unfiltered Post rows
  private def baseSelectQuery: SqlFragment =
    sql"""select
            wp_posts.ID, wp_posts.post_date_gmt, wp_posts.post_content,
            wp_posts.post_title, wp_posts.post_excerpt, wp_posts.post_status,
            wp_posts.comment_status, wp_posts.ping_status, wp_posts.post_name,
            wp_posts.to_ping, wp_posts.pinged, wp_posts.post_modified_gmt,
            wp_posts.post_content_filtered, wp_posts.post_parent, wp_posts.guid,
            wp_posts.menu_order, wp_posts.post_type, wp_posts.post_mime_type,
            wp_posts.comment_count, wp_users.ID as user_id, wp_users.user_login,
            wp_users.user_email, wp_users.display_name,
            json_objectagg(cast(coalesce(wp_postmeta.meta_key, '') as char(255)), coalesce(wp_postmeta.meta_value, ''))
          from wp_posts
          left outer join wp_users on wp_posts.post_author = wp_users.ID
          left outer join wp_postmeta on wp_posts.ID = wp_postmeta.post_id
         group by wp_posts.ID"""

  // Creates a Common Table Expression for all descendant-or-self Post rows of the result of table post_ids
  private def createDescendantOrSelfPostIdsCte: SqlFragment =
    sql"""
         post_tree(post_id, post_name, parent_id) as (
             (select wp_posts.ID, wp_posts.post_name, wp_posts.post_parent
               from wp_posts
              where wp_posts.ID in (select id from post_ids)) union all
             (select wp_posts.ID, wp_posts.post_name, wp_posts.post_parent
                from post_tree
                join wp_posts on post_tree.post_id = wp_posts.post_parent)
         )
       """

  private def mapPostRow(idx: Int, rs: ResultSet): PostRow =
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

  private given JdbcDecoder[PostRow] = JdbcDecoder(rs => idx => mapPostRow(idx, rs))

  def filterPosts(p: Post => Task[Boolean]): RIO[ZConnection, Seq[Post]] =
    // Inefficient
    for {
      sqlFragment <- ZIO.attempt {
        sql"with posts as ($baseSelectQuery) select * from posts"
      }
      posts <- {
        sqlFragment.query[PostRow].selectAll.mapAttempt(PostRow.toPosts)
      }
      filteredPosts <- ZIO.filter(posts)(p)
    } yield filteredPosts

  def findPost(postId: Long): RIO[ZConnection, Option[Post]] =
    val filteredPosts: RIO[ZConnection, Seq[Post]] =
      for {
        sqlFragment <- ZIO.attempt {
          sql"""
             with recursive
             post_ids as (
                 select wp_posts.id from wp_posts where wp_posts.ID = $postId
             ),
             $createDescendantOrSelfPostIdsCte,
             posts as ($baseSelectQuery)
             select * from posts where ID in (select post_id from post_tree)
           """
        }
        posts <- {
          sqlFragment.query[PostRow].selectAll.mapAttempt(PostRow.toPosts)
        }
      } yield posts

    // Return top-level post(s) only, be it with their descendants as children, grandchildren etc.
    filteredPosts.map(_.find(_.postId == postId))
  end findPost

  def findPostByName(name: String): RIO[ZConnection, Option[Post]] =
    val filteredPosts: RIO[ZConnection, Seq[Post]] =
      for {
        sqlFragment <- ZIO.attempt {
          sql"""
             with recursive
             post_ids as (
                 select wp_posts.id from wp_posts where wp_posts.post_name = $name
             ),
             $createDescendantOrSelfPostIdsCte,
             posts as ($baseSelectQuery)
             select * from posts where ID in (select post_id from post_tree)
           """
        }
        posts <- {
          sqlFragment.query[PostRow].selectAll.mapAttempt(PostRow.toPosts)
        }
      } yield posts

    // Return top-level post(s) only, be it with their descendants as children, grandchildren etc.
    filteredPosts.map(_.find(_.postName == name))
  end findPostByName

  private def zeroToNone(v: Long): Option[Long] = if v == 0 then None else Some(v)
  private def emptyToNone(v: String): Option[String] = if v.isEmpty then None else Some(v)

object PostRepoImpl:

  val layer: ZLayer[Any, Nothing, PostRepoImpl] = ZLayer.succeed(PostRepoImpl())

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

    given decoder: JsonDecoder[PostRow] = DeriveJsonDecoder.gen[PostRow]
    given encoder: JsonEncoder[PostRow] = DeriveJsonEncoder.gen[PostRow]

  end PostRow

end PostRepoImpl
