// Copyright (c) 2018 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package ttg.odata.client
package http

import scala.scalajs.js
import js.annotation._
import fs2._
import cats.{Functor,Applicative,FlatMap,Monad}
import cats.data.{EitherT}
import cats.effect._
import js.JSConverters._
import scala.annotation.implicitNotFound
import scala.collection.mutable

/**
  * Decode a Message to a DecodeResult. After decoding you have a DecodeResult
  * which is co-product (either) an error or a value. You can fold on the decode
  * result to work with either side e.g. `mydecoderesult.fold(throw _,
  * identity)`. `EntityDecoder` is really a Kleisli: `Message => DecodeResult`
  * which is `Message[F] => F[Either[DecodeFailure, A]]`. In most client
  * implementations, decoders are only called on successfull results so there
  * the decode method only receives headers and body to decode.
  */
@implicitNotFound("Cannot find instance of EntityDecoder[${T}].")
trait EntityDecoder[F[_], T] { self =>

  def apply(response: Message[F]): DecodeResult[F, T] = decode(response)

  def decode(response: Message[F]): DecodeResult[F, T]

  /** Map into the value part of a DecodeResult. */
  def map[T2](f: T => T2)(implicit M: Functor[F]): EntityDecoder[F, T2] =
    new EntityDecoder[F, T2] {
      override def decode(msg: Message[F]): DecodeResult[F, T2] =
        self.decode(msg).map(f)
    }

  /** Flatmap into the right of the DecodeResult, i.e. the value part not the error. */
  def flatMapR[T2](f: T => DecodeResult[F, T2])(implicit M: Monad[F]): EntityDecoder[F, T2] =
    new EntityDecoder[F, T2] {
      override def decode(msg: Message[F]): DecodeResult[F, T2] =
        self.decode(msg).flatMap(f)
    }

  def handleError(f: DecodeFailure => T)(implicit M: Functor[F]): EntityDecoder[F, T] = transform {
    case Left(e)      => Right(f(e))
    case r @ Right(_) => r
  }

  def handleErrorWith(f: DecodeFailure => DecodeResult[F, T])(implicit M: Monad[F]): EntityDecoder[F, T] =
    transformWith {
      case Left(e)  => f(e)
      case Right(r) => DecodeResult.success(r)
    }

  def bimap[T2](f: DecodeFailure => DecodeFailure, s: T => T2)(implicit M: Functor[F]): EntityDecoder[F, T2] =
    transform {
      case Left(e)  => Left(f(e))
      case Right(r) => Right(s(r))
    }

  /**
    * Try this decoder then other if this decoder returns a decode failure. Due
    * to the process-once nature of the body, the orElse must really check
    * headers or other information to allow orElse to compose correctly.
    */
  def orElse[T2 >: T](other: EntityDecoder[F, T2])(implicit M: Monad[F]): EntityDecoder[F, T2] = {
    new EntityDecoder[F, T2] {
      override def decode(msg: Message[F]): DecodeResult[F, T2] = {
        self.decode(msg) orElse other.decode(msg)
      }
    }
  }

  /** Covariant widenening via cast. We do it for you so you don't have to. */
  def widen[T2 >: T]: EntityDecoder[F, T2] = this.asInstanceOf[EntityDecoder[F, T2]]

  /** Transform a decode result into another decode result. */
  def transform[T2](t: Either[DecodeFailure, T] => Either[DecodeFailure, T2])(
      implicit M: Functor[F]): EntityDecoder[F, T2] =
    new EntityDecoder[F, T2] {
      override def decode(message: Message[F]): DecodeResult[F, T2] =
        self.decode(message).transform(t)
    }

  def biflatMap[T2](f: DecodeFailure => DecodeResult[F, T2], s: T => DecodeResult[F, T2])(
      implicit M: FlatMap[F]): EntityDecoder[F, T2] =
    transformWith {
      case Left(e)  => f(e)
      case Right(r) => s(r)
    }

  def transformWith[T2](f: Either[DecodeFailure, T] => DecodeResult[F, T2])(
      implicit M: FlatMap[F]): EntityDecoder[F, T2] =
    new EntityDecoder[F, T2] {
      override def decode(msg: Message[F]): DecodeResult[F, T2] =
        DecodeResult(
          M.flatMap(self.decode(msg).value)(r => f(r).value)
        )
    }
}

/** 
 * @todo Define Alternative[EntityDecoder[F[_]]]? We already have `orElse`.
 */
object EntityDecoder extends EntityDecoderInstances {

  /** Summoner. */
  def apply[F[_], T](implicit ev: EntityDecoder[F, T]): EntityDecoder[F, T] = ev

  /** Lift function to create a decoder. */
  def instance[F[_], T](run: Message[F] => DecodeResult[F, T]): EntityDecoder[F, T] =
    new EntityDecoder[F, T] {
      def decode(response: Message[F]) = run(response)
    }
}

/**
  * EntityDecoder instances specific to the type you want to "output" from the
  * decoding process. Currently tied to `IO`.
  */
trait EntityDecoderInstances {
  import ttg.scalajs.common._

  /**
    * Decode body to text. Since the body in a response is already text this is
    * the simplest decoder. We don't worry about charsets here but we should.
    */
  implicit def TextDecoder[F[_]: Functor]: EntityDecoder[F, String] =
    EntityDecoder.instance { msg =>
      DecodeResult.success(msg.body.content)
    }

  /**
    * Parsed into JSON using JSON.parse(). JSON parse could return a simple
    * value, not a JS object. Having said that, all response bodies (for valid
    * responses) from the server are objects.
    */
  def jsDynamicDecoder[F[_]: Functor](
    reviver: Option[Reviver] = None): EntityDecoder[F, js.Dynamic] =
    EntityDecoder.instance[F, js.Dynamic](
      msg => DecodeResult.success(Functor[F].map(msg.body.content)(js.JSON.parse(_, reviver.orUndefined.orNull)))
    )

  implicit def JsDynamicDecoder[F[_]: Functor]: EntityDecoder[F, js.Dynamic] =
    jsDynamicDecoder()

  /** Filter on the JSON value. Create DecodeFailure if filter func returns
   * false.
   */
  def JsonDecoderValidate[F[_]: Monad](
    f: js.Dynamic => Boolean,
    failedMsg: String = "Failed validation.",
    reviver: Option[Reviver] = None): EntityDecoder[F, js.Dynamic] =
    EntityDecoder.instance{ msg =>
      jsDynamicDecoder(reviver).decode(msg).flatMap { v =>
        if (f(v)) EitherT.rightT(v)
        else EitherT.leftT(MessageBodyFailure(failedMsg))
      }
    }

  /**
    * Decode the body as json and cast to A instead of JSONDecoder which casts
    * the body to js.Dynamic. Typebounds implies that non-native scala JS traits
    * can use this decoder.
    */
  def jsObjectDecoder[F[_]: Functor, A <: js.Object](
    reviver: Option[Reviver] = None): EntityDecoder[F, A] =
    jsDynamicDecoder(reviver).map(_.asInstanceOf[A])

  /**
   * Decode a js.Object => A. 
   */
  implicit def JsObjectDecoder[F[_]: Functor, A <: js.Object]: EntityDecoder[F,A] =
    jsObjectDecoder()

  /**
    * Ignore the response completely (status and body) and return decode "unit"
    * success. You typically use this decoder with a client type parameter of
    * `Unit` and when you only want to check that a successful status code was
    * returned or error out otherwise.
    */
  implicit def void[F[_]: Applicative]: EntityDecoder[F, Unit] =
    EntityDecoder.instance{ _ =>
      DecodeResult.success(())
    }
}
