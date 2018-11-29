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
  * which is `Message[F] => F[Either[DecodeFailure, A]]`.
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

  /** GUID regex. (scala) */
  val reg = """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""".r

  /** Dates from dynamics server. (js regexp) */
  protected val dateRegex =
    new js.RegExp("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2}(?:\.\d*)?)Z$""")

  /** JSON reviver that matches nothing. */
  val undefinedReviver = js.undefined.asInstanceOf[Reviver]

  /** JSON Date reviver based on ISO string format `dateRegex`. (js) */
  val dateReviver: Reviver =
    (key, value) => {
      if (js.typeOf(value) == "string") {
        val a = dateRegex.exec(value.asInstanceOf[String])
        if (a != null)
          new js.Date(
            js.Date.UTC(
              a(1).get.toInt,
              a(2).get.toInt - 1,
              a(3).get.toInt,
              a(4).get.toInt,
              a(5).get.toInt,
              a(6).get.toInt
            ))
        else value
      } else value
    }

  /**
    * A decoder that only looks at the header for an OData-EntityId
    * (case-insensitive) value and returns that, otherwise fail. To ensure that
    * the id is returned in the header, you must make sure that
    * return=representation is *not* set in the Prefer headers when the HTTP
    * call is issued.
    */
  def ReturnedIdDecoder[F[_]: Applicative]: EntityDecoder[F, String] = EntityDecoder { msg =>
    (msg.headers.get("OData-EntityId") orElse msg.headers.get("odata-entityid"))
      .map(_(0))
      .flatMap(reg.findFirstIn(_))
      .fold(DecodeResult.failure[F, String](MissingExpectedHeader("OData-EntityId")))(id => DecodeResult.success(id))
  }

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
  implicit def JSONDecoder[F[_]: Functor](reviver: Option[Reviver] = None): EntityDecoder[F, js.Dynamic] =
    EntityDecoder.instance[F, js.Dynamic](
      msg => DecodeResult.success(Functor[F].map(msg.body.content)(js.JSON.parse(_, reviver.orUndefined.orNull)))
    )

  /** Filter on JSON value. Create DecodeFailure if filter func returns false. */
  def JSONDecoderValidate[F[_]: Monad](
    f: js.Dynamic => Boolean,
    failedMsg: String = "Failed validation.",
    reviver: Option[Reviver] = None): EntityDecoder[F, js.Dynamic] =
    EntityDecoder.instance{ msg =>
      JSONDecoder(reviver).decode(msg).flatMap { v =>
        if (f(v)) EitherT.rightT(v)
        else EitherT.leftT(MessageBodyFailure(failedMsg))
      }
    }

  /**
    * Decode the body as json and cast to A instead of JSONDecoder which casts
    * the body to js.Dynamic. Typebounds implies that non-native scala JS traits
    * can use this decoder.
    */
  implicit def JsObjectDecoder[F[_]: Functor, A <: js.Object](reviver: Option[Reviver] = None): EntityDecoder[F, A] =
    JSONDecoder(reviver).map(_.asInstanceOf[A])

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

  /**
    * Check for value array and if there is a value array return the first
    * element.  Otherwise cast the entire response to A directly and return
    * it. Either way, return a single value of type `T`. Because of these
    * assumptions, you could get undefined behavior. If there is more than
    * one element in the array, return an OnlyOneExpected error.
    *
    * If you are assuming a different underlying decode approach to the raw http
    * body, you need to write your own wrapper to detect the "value" array and
    * decide how to decode based on its presence. That's because its assumed in
    * this function that we will decode to a js.Object first to check for the
    * value array in the response body. This should really be called
    * `FirstElementOfValueArrayIfThereIsOneOrCastWholeMessage`.
    */
  def ValueWrapper[F[_]: Monad, A <: js.Object] =
    JsObjectDecoder[F, ValueArrayResponse[A]]().flatMapR[A] { arrresp =>
      // if no "value" array, assume its safe to cast to a single A
      arrresp.value.fold(DecodeResult.success[F, A](arrresp.asInstanceOf[A])) { arr =>
        if (arr.size > 0) DecodeResult.success(arr(0))
        else DecodeResult.failure(OnlyOneExpected(s"found ${arr.size} elements in 'value' field"))
      }
    }

  /** Use this when you expect only one value in an array or return an error.
    * @example {{{
    * val q = QuerySpec(...) // select value by name, which should be unique but is not a PK
    *  dynclient.getOne[Blah](q.url("entitysetname"))(ExpectOnlyOne)
    *    .map( ... )
    *    .recover( ... )
    * }}}
    */
  def ExpectOnlyOne[F[_]: Monad, A <: js.Object] = ValueWrapper[F, A]

  /**
    * Transform a `DecodeFailure(OnlyOneExpected)` to None if present, otherwise
    * Some. Use as an explicit encoder and use a type of `Option[A]` e.g.
   * `client.getOne[Option[A]](..)(ExpectOnlyOneToOption)`.
    */
  def ExpectOnlyOneToOption[F[_]: Monad, A <: js.Object] =
    ExpectOnlyOne[F, A].transformWith[Option[A]] {
      case Right(r)                    => DecodeResult.success(Some(r))
      case Left(OnlyOneExpected(_, _)) => DecodeResult.success(Option.empty[A])
      case Left(t) => DecodeResult.failure(t)
    }

  /**
    * Decode based on the expectation of a "value" field name that has an array
    * of "A" values. The returned value is a js Array not a scala collection.
    * If "value" fieldname is undefined, return an empty array freshly
    * allocated.
    */
  def ValueArrayDecoder[F[_]: Functor, A <: scala.Any]: EntityDecoder[F, js.Array[A]] =
    JsObjectDecoder[F, ValueArrayResponse[A]]().map(_.value.getOrElse(js.Array[A]()))

  /**
    * Decode based on the expectation of a single value in a fieldname called
    * "value". You might get this when you navigate to a simple/single value
    * property on a specific entity
    * e.g. '/myentities(theguid)/somesimpleattribute'. A null value or undefined
    * value is automatically taken into account in the returned
    * Option. Preversely, you could assume that "js.Array[YourSomething]" is the
    * single value and use that instead of [[ValueArrayDecoder]].
    */
  def SingleValueDecoder[F[_]: Functor, A <: scala.Any]: EntityDecoder[F, js.UndefOr[A]] =
    JsObjectDecoder[F,SingleValueResponse[A]]().map(_.value)
}
