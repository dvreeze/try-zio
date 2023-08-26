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
import java.time.LocalDateTime
import java.time.ZoneId

import scala.util.Try
import scala.util.chaining.*

import eu.cdevreeze.tryzio.wordpress.model.*
import eu.cdevreeze.tryzio.wordpress.repo.PostRepoImpl2.PostRow
import zio.*
import zio.jdbc.*
import zio.json.*

/**
 * Alternative concrete repository of posts, exploiting JSON "rows" as query results.
 *
 * @author
 *   Chris de Vreeze
 */
final class PostRepoImpl2() extends PostRepo:

  // Common Table Expression body for the unfiltered Post rows
  private def baseSelectQuery: SqlFragment =
    sql"""select
            wp_posts.ID,
            json_object(
                'postId', wp_posts.ID,
                'postDate', if(extract(year from wp_posts.post_date_gmt) = 0, '1970-01-01T00:00:00Z', concat(date_format(wp_posts.post_date_gmt, '%Y-%m-%dT%H:%i:%s'), 'Z')),
                'postContentOption', wp_posts.post_content,
                'postTitle', wp_posts.post_title,
                'postExcerpt', wp_posts.post_excerpt,
                'postStatus', wp_posts.post_status,
                'commentStatus', wp_posts.comment_status,
                'pingStatus', wp_posts.ping_status,
                'postName', wp_posts.post_name,
                'toPing', wp_posts.to_ping,
                'pinged', wp_posts.pinged,
                'postModified', if(extract(year from wp_posts.post_modified_gmt) = 0, '1970-01-01T00:00:00Z', concat(date_format(wp_posts.post_modified_gmt, '%Y-%m-%dT%H:%i:%s'), 'Z')),
                'postContentFilteredOption', wp_posts.post_content_filtered,
                'parentOpt', nullif(wp_posts.post_parent, 0),
                'guid', wp_posts.guid,
                'menuOrder', wp_posts.menu_order,
                'postType', wp_posts.post_type,
                'postMimeType', wp_posts.post_mime_type,
                'commentCount', wp_posts.comment_count,
                'postAuthorOption', if(wp_posts.post_author = 0, cast(null as json),
                    json_object(
                        'userId', wp_users.ID,
                        'userLogin', wp_users.user_login,
                        'userEmail', wp_users.user_email,
                        'displayName', wp_users.display_name)),
                'postMeta', json_objectagg(cast(coalesce(wp_postmeta.meta_key, '') as char(255)), coalesce(wp_postmeta.meta_value, '')))
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

  private def mapPostRow(rs: ResultSet, idx: Int): PostRow =
    val postId: Long = rs.getLong(1)
    JsonDecoder[PostRow]
      .decodeJson(rs.getString(2))
      .fold(sys.error, identity)
      .ensuring(_.postId == postId)

  private given JdbcDecoder[PostRow] = JdbcDecoder(mapPostRow.curried)

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

object PostRepoImpl2:

  val layer: ZLayer[Any, Nothing, PostRepoImpl2] = ZLayer.succeed(PostRepoImpl2())

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
