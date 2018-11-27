// Copyright (c) 2018 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ttg.odata.client

import scala.scalajs.js
import collection.mutable
import concurrent.duration.FiniteDuration

import cats._
import cats.data._
import cats.effect._

/**
 * HTTP client based on the http4s design but simplified to handle only strict
 * message bodies and non-"cats Resource" based HTTP responses. The client is
 * scala.js based and not for the JVM per se.
 *
 */
package object http {

  type Middleware[F[_]]         = Client[F] => Client[F]

  /**
    * Non-streaming but good enough for our needs. IO is used explicitly but
    * since we have strict bodies, why not use "Id" in the short term? Entity is
    * the body part of a Message.
    *
    * @todo Make F so we can use Id or something simpler.
    */
  type Entity = IO[String]

  /** Basic headers are a dict of strings. */
  type HttpHeaders = collection.immutable.Map[String, Seq[String]]

  /**
   * When decoding a response body, either you get an A or a DecodeFailure. The
    * effect may also carry an exception.  EitherT has a bunch of combinators.
    *
    * @see https://typelevel.org/cats/api/cats/data/EitherT.html
    */
  type DecodeResult[F[_], A] = EitherT[F, DecodeFailure, A]

  /** This old type spec assumes F can carry an error, which may not be true and
   * we may need to detect the request type e.g. GET as part of the criteria.
   */
  type RetryPolicy[F[_]] =
    (HttpRequest[F], Either[Throwable, HttpResponse[F]], Int) => Option[FiniteDuration]

  /** Reviver used when decoding using javascript engine decoder. */
  type Reviver = js.Function2[js.Any, js.Any, js.Any]
}