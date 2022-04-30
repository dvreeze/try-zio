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

package eu.cdevreeze.tryzio.wordpress

import java.time.Instant

import scala.annotation.targetName

import zio.json.*

/**
 * Model of (part of) Wordpress database data.
 *
 * @author
 *   Chris de Vreeze
 */
object model:

  // Data model for querying

  final case class Term(termId: Long, name: String, slug: String, termGroupOption: Option[Long])

  object Term:
    given decoder: JsonDecoder[Term] = DeriveJsonDecoder.gen[Term]
    given encoder: JsonEncoder[Term] = DeriveJsonEncoder.gen[Term]

  final case class TermTaxonomy(
      termTaxonomyId: Long,
      term: Term,
      taxonomy: String,
      description: String,
      children: Seq[TermTaxonomy],
      count: Int
  )

  object TermTaxonomy:
    given decoder: JsonDecoder[TermTaxonomy] = DeriveJsonDecoder.gen[TermTaxonomy]
    given encoder: JsonEncoder[TermTaxonomy] = DeriveJsonEncoder.gen[TermTaxonomy]

  enum PostStatus(val stringValue: String):
    case Draft extends PostStatus("draft")
    case AutoDraft extends PostStatus("auto-draft")
    case Publish extends PostStatus("publish")
    case Inherit extends PostStatus("inherit")
    case Future extends PostStatus("future")
    case Unknown extends PostStatus("?")

  object PostStatus:
    def parse(s: String): PostStatus =
      PostStatus.values.find(_.stringValue == s).getOrElse(sys.error(s"Unknown PostStatus case: '$s'"))
    given decoder: JsonDecoder[PostStatus] = JsonDecoder[String].map(PostStatus.parse)
    given encoder: JsonEncoder[PostStatus] = JsonEncoder[String].contramap(_.stringValue)

  enum CommentStatus(val stringValue: String):
    case Open extends CommentStatus("open")
    case Closed extends CommentStatus("closed")
    case Unknown extends CommentStatus("?")

  object CommentStatus:
    def parse(s: String): CommentStatus =
      CommentStatus.values.find(_.stringValue == s).getOrElse(sys.error(s"Unknown CommentStatus case: '$s'"))
    given decoder: JsonDecoder[CommentStatus] = JsonDecoder[String].map(CommentStatus.parse)
    given encoder: JsonEncoder[CommentStatus] = JsonEncoder[String].contramap(_.stringValue)

  enum PostType(val stringValue: String):
    case Page extends PostType("page")
    case Post extends PostType("post")
    case Attachment extends PostType("attachment")
    case NavMenuItem extends PostType("nav_menu_item")
    case WpGlobalStyles extends PostType("wp_global_styles")
    case Unknown extends PostType("?")

  object PostType:
    def parse(s: String): PostType =
      PostType.values.find(_.stringValue == s).getOrElse(sys.error(s"Unknown PostType case: '$s'"))
    given decoder: JsonDecoder[PostType] = JsonDecoder[String].map(PostType.parse)
    given encoder: JsonEncoder[PostType] = JsonEncoder[String].contramap(_.stringValue)

  final case class Post(
      postId: Long,
      postAuthorOption: Option[Post.User],
      postDate: Instant,
      postContentOption: Option[String], // optional, to leave option open to leave it out
      postTitle: String,
      postExcerpt: String,
      postStatus: PostStatus,
      commentStatus: CommentStatus,
      pingStatus: String,
      postName: String, // post password left out
      toPing: String,
      pinged: String,
      postModified: Instant,
      postContentFilteredOption: Option[String], // optional, to leave option open to leave it out
      children: Seq[Post],
      guid: String,
      menuOrder: Int,
      postType: PostType,
      postMimeType: String,
      commentCount: Int,
      metaData: Map[String, String]
  )

  object Post:
    final case class User(userId: Long, userLogin: String, userEmail: String, displayName: String)
    object User:
      given decoder: JsonDecoder[User] = DeriveJsonDecoder.gen[User]
      given encoder: JsonEncoder[User] = DeriveJsonEncoder.gen[User]
    given decoder: JsonDecoder[Post] = DeriveJsonDecoder.gen[Post]
    given encoder: JsonEncoder[Post] = DeriveJsonEncoder.gen[Post]

end model
