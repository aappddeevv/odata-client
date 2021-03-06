// Copyright (c) 2018 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ttg
package client
package odata

import scala.scalajs.js
import js._
import cats._
import cats.effect._
import fs2._

import http._
import ttg.scalajs.common.fs2helpers._

/**
  * Misc stream methods.
  */
trait ClientStreamOps[
  F[_],
  PreferOptions <: BasicPreferOptions,
  RequestOptions <: BasicRequestOptions[PreferOptions]
] {
  self: ClientInfrastructure[F, PreferOptions, RequestOptions] =>

  // move from call site to declaration site
  implicit protected val compiler: Stream.Compiler[F,F]

  private type Intermediate[A] = (js.Array[A], Option[String])

  protected def defaultParse(content: String): js.Dynamic =
    js.JSON.parse(content)

  /**
    * Get a list of values as a stream. Follows @odata.nextLink. For now, the
    * caller must decode external to this method.
    */
  protected def getListAsStream[A](url: String, headers: HttpHeaders): Stream[F, A] = {
    val str: Stream[F, js.Array[A]] = Stream.unfoldEval(Option(url)) {
      // Return a F[Option[(Seq[A],Option[String])]]
      case Some(nextLink) =>
        val request = HttpRequest[F](Method.GET, nextLink, headers=headers, body=emptyBody)
        http.fetch[Option[Intermediate[A]]](request) {
          case Status.Successful(resp) =>
            F.map(resp.body.content){ str =>
              val odata = defaultParse(str).asInstanceOf[ValueArrayResponse[A]]
              // if (logger.isDebugEnabled())
              //   logger.debug(s"getListStream: body=$str\nodata=${PrettyJson.render(odata)}"
              val a = odata.value getOrElse js.Array()
              //println(s"getList: a=$a,\n${PrettyJson.render(a(0).asInstanceOf[js.Object])}")
              Option((a, odata.nextLink.toOption))
            }
          case failedResponse =>
            mkUnexpectedError(s"getListStream $url", request, failedResponse)
        }
      case None => F.pure(Option.empty[Intermediate[A]])
    }
    // Flatten the array (converted to seq) chunks from each unfold iteration
    str.map(_.toSeq).flatMap(Stream.emits)
  }
}

/** Operations for request entity collections. */
trait CollectionOps[
  F[_],
  PreferOptions <: BasicPreferOptions,
  RequestOptions <: BasicRequestOptions[PreferOptions]
] extends ClientStreamOps[F, PreferOptions, RequestOptions] {
  self: ClientInfrastructure[F, PreferOptions, RequestOptions] =>

  /**
    * Get a list of values. Follows @data.nextLink but accumulates all the
    * results into memory. Prefer [[getListStream]]. For now, the caller must
    * decode external to this method. The url is typically created from a
    * QuerySpec.
    *
    * @see getListStream
    */
  def getList[A <: js.Any](url: String, opts:Option[RequestOptions]=None): F[Vector[A]] =
    getListAsStream[A](url, toHeaders(opts)).compile.toVector

  /**
    * Get a list of values as a stream. Follows @odata.nextLink. For now, the
    * caller must decode external to this method. The url is usually created
    * from a QuerySpec e.g. `val q = QuerySpec(); val url = q.url("entitysetname")`.
    */
  def getListStream[A <: js.Any](url: String, opts:Option[RequestOptions]=None): Stream[F, A] =
    getListAsStream[A](url, toHeaders(opts))
}
