// Copyright (c) 2018 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ttg
package client
package http

import fs2._
import cats._
import cats.implicits._

/** Boundary marker for batch or changesets. */
final case class Boundary(value: String) extends AnyVal

object Boundary extends RenderConstants {
  private[http] def generate(): String = java.util.UUID.randomUUID.toString

  /** Make a boundary with a random UUID. */
  def mkBoundary(prefix: String = "boundary_"): Boundary = Boundary(prefix + generate())

  def renderBoundary(boundary: Boundary, close: Boolean): String = {
    val sb = new StringBuilder()
    sb.append(CRLF + "--" + boundary.value)
    if (close) sb.append("--")
    sb.append(CRLF)
    sb.toString()
  }
}

/**
 * One part of a multipart request. There are only two subtypes, one for a
 * request directly in the multipart message and the other for a
 * changeset. Deletes, updates and inserts must be in a changeset.
 */
sealed trait Part[B1[_]] {
  def xtra: HttpHeaders
}

object Part {
}

/** Single request. Either standalone or in a changeset. Content-Type and
  * Content-Transfer-Encoding is added to each part prior to the request being
  * written. Headers in request can be overriden and added to using xtra.
  * @paarm request HttpRequest
  * @param xtra Extra headers after the boundary but not in the actual request.
  */
final case class SinglePart[B1[_]](
  request: HttpRequest[B1,String],
  xtra: HttpHeaders = HttpHeaders.empty
) extends Part[B1]

/**
  * Changeset "part". Headers are written for Content-Type and
  * Content-Transfer-Encoding at the start of each Part's boundary. These can be
  * overwritten or added to using xtra. A random Content-ID is added if one is
  * not present.
  * @param requests Set of changset requests.
  * @param bounday The changeset boundary.
  * @param xtra Extra headers after the changeset boundary but not in the actual requests.
  */
final case class ChangeSet[B1[_]](parts: Seq[SinglePart[B1]],
                                 boundary: Boundary = Boundary.mkBoundary("changeset_"),
                                 xtra: HttpHeaders = HttpHeaders.empty)
    extends Part[B1]

final case class EmptyPart[B1[_]]() extends Part[B1] {
  val xtra: HttpHeaders = HttpHeaders.empty
}

object ChangeSet {

  /** ChangeSet from requests. */
  def fromRequests[B1[_]](requests: Seq[HttpRequest[B1,String]]): ChangeSet[B1] =
    ChangeSet(requests.map(SinglePart(_)))
}

/**
  * Multipart composed of a list of parts: individual requests and changesets.
  * Despite its name, it does not inherit from Part.
  * @param parts Sequence of Parts.
  * @param boundary Batch boundary.
  */
final case class Multipart[B1[_]](parts: Seq[Part[B1]], boundary: Boundary = Boundary.mkBoundary())

trait RenderConstants {
  val MediaType = "multipart/mixed"
  val CRLF      = "\r\n"
}

object Multipart extends RenderConstants {
  import Boundary.renderBoundary

  /** Requests are bundled into a changeset. */
  def fromRequests[B1[_]](requests: Seq[HttpRequest[B1,String]]): Multipart[B1] =
    Multipart(Seq(ChangeSet.fromRequests[B1](requests)))

  /**
    * Render a batch boundary for each part, render each part, render the closing batch boundary.
    */
  def render[B1[_]: Traverse: Monad](m: Multipart[B1]): B1[String] = {
    val B1 = Monad[B1]
    val partsAsTasks = m.parts.map { p =>
      B1.map(renderPart(p))(rest => renderBoundary(m.boundary, false) + rest)
    }
    B1.map(partsAsTasks.toList.sequence)(all => all.mkString("") + renderBoundary(m.boundary, true))
  }

  val StandardPartHeaders = HttpHeaders("Content-Transfer-Encoding" -> "binary", "Content-Type" -> "application/http")

  /** Render a "mini-request". Each batch part must have a Host header or a full URL.
    *
    * TODO: There may be an extra space between a changeset boundary and the start of
    * a changeset item's boundary header.
    */
  def renderPart[B1[_]: Traverse: Monad](p: Part[B1]): B1[String] = {
    val B1 = Monad[B1]
    p match {
      case SinglePart(req, xtra) =>
        val partHeaders = StandardPartHeaders ++ xtra
        val method      = s"${req.method.asString} ${req.path} HTTP/1.1"

        // this seems disruptive, but its malformed if its not right...
        if (!req.headers.contains("Host") && !req.path.startsWith("http"))
          throw new IllegalArgumentException(
            "OData multitpart requires each part request to have a Host header or a full path URL.")

        B1.map(req.body){ contentstr =>
          HttpHeaders.render(partHeaders) + CRLF +
          method + CRLF +
          HttpHeaders.render(req.headers) + CRLF +
          contentstr
        }

      case ChangeSet(parts, b, xtra) =>
        val partHeaders = HttpHeaders("Content-Type" -> (Multipart.MediaType + "; boundary=" + b.value)) ++ xtra
        val partsAsTasks = parts.map { p =>
          // add Content-ID if not present in the xtra headers in the part.
          val withContentId =
            HttpHeaders.seqPairs(
              "Content-ID" ->
                p.xtra.get("Content-ID").getOrElse(Seq(Boundary.generate())))
          // Ensure type=entry if not present in the seq attached to content-type header or
          // add the entire content-type header if its missing.
          val modifiedOrOrigCT =
            p.request.headers.get("Content-Type").map(seq => (seq, seq.indexOf("type"))).collect {
              case (seq, idx) if (idx >= 0) => seq
              case (seq, _)                 => seq :+ "type=entry"
            }
          val newCT: HttpHeaders =
            HttpHeaders.seqPairs("Content-Type" -> Seq("application/json", "type=entry")) ++
          modifiedOrOrigCT.map(ctvalue =>
            HttpHeaders.seqPairs("Content-Type" -> ctvalue)).getOrElse(HttpHeaders.empty)

          val modified =
            p.copy(request = p.request.copy(headers =
              p.request.headers ++ newCT), xtra = p.xtra ++ withContentId)
          B1.map(renderPart(modified))(rest => renderBoundary(b, false) + rest)
        }
        B1.map(partsAsTasks.toList.sequence)(
          all => HttpHeaders.render(partHeaders) + CRLF + all.mkString("") + renderBoundary(b, true))

      case EmptyPart() => Monad[B1].pure("")
    }
  }
}
