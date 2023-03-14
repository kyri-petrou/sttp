package sttp.client4

import sttp.capabilities.{Effect, Streams, WebSockets}
import sttp.client4.internal.SttpFile
import sttp.model.ResponseMetadata
import sttp.model.internal.Rfc3986
import sttp.ws.{WebSocket, WebSocketFrame}

import scala.collection.immutable.Seq
import scala.util.{Failure, Success, Try}

/** Describes how the response body of a request should be handled. A number of `as<Type>` helper methods are available
  * as part of [[SttpApi]] and when importing `sttp.client4._`. These methods yield specific implementations of this
  * trait, which can then be set on a [[Request]], [[StreamRequest]], [[WebSocketRequest]] or
  * [[WebSocketStreamRequest]], depending on the response type.
  *
  * @tparam T
  *   Target type as which the response will be read.
  * @tparam R
  *   The backend capabilities required by the response description. This might be `Any` (no requirements),
  *   [[sttp.capabilities.Effect]] (the backend must support the given effect type), [[sttp.capabilities.Streams]] (the
  *   ability to send and receive streaming bodies) or [[sttp.capabilities.WebSockets]] (the ability to handle websocket
  *   requests).
  */
trait ResponseAsDelegate[+T, -R] {
  def delegate: GenericResponseAs[T, R]
  def show: String = delegate.show
}

/** Describes how the response body of a [[Request]] should be handled.
  *
  * Apart from the basic cases (ignoring, reading as a byte array or file), response body descriptions can be mapped
  * over, to support custom types. The mapping can take into account the [[ResponseMetadata]], that is the headers and
  * status code. Responses can also be handled depending on the response metadata. Finally, two response body
  * descriptions can be combined (with some restrictions).
  *
  * A number of `as<Type>` helper methods are available as part of [[SttpApi]] and when importing `sttp.client4._`.
  *
  * @tparam T
  *   Target type as which the response will be read.
  */
case class ResponseAs[+T](delegate: GenericResponseAs[T, Any]) extends ResponseAsDelegate[T, Any] {
  def map[T2](f: T => T2): ResponseAs[T2] = ResponseAs(delegate.mapWithMetadata { case (t, _) => f(t) })
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): ResponseAs[T2] = ResponseAs(delegate.mapWithMetadata(f))

  def showAs(s: String): ResponseAs[T] = ResponseAs(delegate.showAs(s))
}

object ResponseAs {
  implicit class RichResponseAsEither[A, B](ra: ResponseAs[Either[A, B]]) {
    def mapLeft[L2](f: A => L2): ResponseAs[Either[L2, B]] = ra.map(_.left.map(f))
    def mapRight[R2](f: B => R2): ResponseAs[Either[A, R2]] = ra.map(_.right.map(f))

    /** If the type to which the response body should be deserialized is an `Either[A, B]`:
      *   - in case of `A`, throws as an exception / returns a failed effect (wrapped with an [[HttpError]] if `A` is
      *     not yet an exception)
      *   - in case of `B`, returns the value directly
      */
    def getRight: ResponseAs[B] =
      ra.mapWithMetadata { case (t, meta) =>
        t match {
          case Left(a: Exception) => throw a
          case Left(a)            => throw HttpError(a, meta.code)
          case Right(b)           => b
        }
      }
  }

  implicit class RichResponseAsEitherResponseException[HE, DE, B](
      ra: ResponseAs[Either[ResponseException[HE, DE], B]]
  ) {

    /** If the type to which the response body should be deserialized is an `Either[ResponseException[HE, DE], B]`,
      * either throws the [[DeserializationException]], returns the deserialized body from the [[HttpError]], or the
      * deserialized successful body `B`.
      */
    def getEither: ResponseAs[Either[HE, B]] =
      ra.map {
        case Left(HttpError(he, _))               => Left(he)
        case Left(d: DeserializationException[_]) => throw d
        case Right(b)                             => Right(b)
      }
  }

  /** Returns a function, which maps `Left` values to [[HttpError]] s, and attempts to deserialize `Right` values using
    * the given function, catching any exceptions and representing them as [[DeserializationException]] s.
    */
  def deserializeRightCatchingExceptions[T](
      doDeserialize: String => T
  ): (Either[String, String], ResponseMetadata) => Either[ResponseException[String, Exception], T] = {
    case (Left(s), meta) => Left(HttpError(s, meta.code))
    case (Right(s), _)   => deserializeCatchingExceptions(doDeserialize)(s)
  }

  /** Returns a function, which attempts to deserialize `Right` values using the given function, catching any exceptions
    * and representing them as [[DeserializationException]] s.
    */
  def deserializeCatchingExceptions[T](
      doDeserialize: String => T
  ): String => Either[DeserializationException[Exception], T] =
    deserializeWithError((s: String) =>
      Try(doDeserialize(s)) match {
        case Failure(e: Exception) => Left(e)
        case Failure(t: Throwable) => throw t
        case Success(t)            => Right(t): Either[Exception, T]
      }
    )

  /** Returns a function, which maps `Left` values to [[HttpError]] s, and attempts to deserialize `Right` values using
    * the given function.
    */
  def deserializeRightWithError[E: ShowError, T](
      doDeserialize: String => Either[E, T]
  ): (Either[String, String], ResponseMetadata) => Either[ResponseException[String, E], T] = {
    case (Left(s), meta) => Left(HttpError(s, meta.code))
    case (Right(s), _)   => deserializeWithError(doDeserialize)(implicitly[ShowError[E]])(s)
  }

  /** Returns a function, which keeps `Left` unchanged, and attempts to deserialize `Right` values using the given
    * function. If deserialization fails, an exception is thrown
    */
  def deserializeRightOrThrow[E: ShowError, T](
      doDeserialize: String => Either[E, T]
  ): Either[String, String] => Either[String, T] = {
    case Left(s)  => Left(s)
    case Right(s) => Right(deserializeOrThrow(doDeserialize)(implicitly[ShowError[E]])(s))
  }

  /** Converts a deserialization function, which returns errors of type `E`, into a function where errors are wrapped
    * using [[DeserializationException]].
    */
  def deserializeWithError[E: ShowError, T](
      doDeserialize: String => Either[E, T]
  ): String => Either[DeserializationException[E], T] =
    s =>
      doDeserialize(s) match {
        case Left(e)  => Left(DeserializationException(s, e))
        case Right(b) => Right(b)
      }

  /** Converts a deserialization function, which returns errors of type `E`, into a function where errors are thrown as
    * exceptions, and results are returned unwrapped.
    */
  def deserializeOrThrow[E: ShowError, T](doDeserialize: String => Either[E, T]): String => T =
    s =>
      doDeserialize(s) match {
        case Left(e)  => throw DeserializationException(s, e)
        case Right(b) => b
      }
}

/** Describes how the response body of a [[StreamRequest]] should be handled.
  *
  * The stream response can be mapped over, to support custom types. The mapping can take into account the
  * [[ResponseMetadata]], that is the headers and status code.
  *
  * A number of `asStream[Type]` helper methods are available as part of [[SttpApi]] and when importing
  * `sttp.client4._`.
  *
  * @tparam T
  *   Target type as which the response will be read.
  * @tparam S
  *   The type of stream, used to receive the response body bodies.
  */
case class StreamResponseAs[+T, S](delegate: GenericResponseAs[T, S]) extends ResponseAsDelegate[T, S] {
  def map[T2](f: T => T2): StreamResponseAs[T2, S] =
    StreamResponseAs(delegate.mapWithMetadata { case (t, _) => f(t) })
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): StreamResponseAs[T2, S] =
    StreamResponseAs(delegate.mapWithMetadata(f))

  def showAs(s: String): StreamResponseAs[T, S] = new StreamResponseAs(delegate.showAs(s))
}

/** Describes how the response of a [[WebSocketRequest]] should be handled.
  *
  * The websocket response can be mapped over, to support custom types. The mapping can take into account the
  * [[ResponseMetadata]], that is the headers and status code. Responses can also be handled depending on the response
  * metadata.
  *
  * A number of `asWebSocket` helper methods are available as part of [[SttpApi]] and when importing `sttp.client4._`.
  *
  * @tparam T
  *   Target type as which the response will be read.
  */
case class WebSocketResponseAs[F[_], +T](delegate: GenericResponseAs[T, Effect[F] with WebSockets])
    extends ResponseAsDelegate[T, Effect[F] with WebSockets] {
  def map[T2](f: T => T2): WebSocketResponseAs[F, T2] =
    WebSocketResponseAs(delegate.mapWithMetadata { case (t, _) => f(t) })
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): WebSocketResponseAs[F, T2] =
    WebSocketResponseAs(delegate.mapWithMetadata(f))

  def showAs(s: String): WebSocketResponseAs[F, T] = new WebSocketResponseAs(delegate.showAs(s))
}

/** Describes how the response of a [[WebSocketStreamRequest]] should be handled.
  *
  * The websocket response can be mapped over, to support custom types. The mapping can take into account the
  * [[ResponseMetadata]], that is the headers and status code. Responses can also be handled depending on the response
  * metadata.
  *
  * A number of `asWebSocket` helper methods are available as part of [[SttpApi]] and when importing `sttp.client4._`.
  *
  * @tparam T
  *   Target type as which the response will be read.
  */
case class WebSocketStreamResponseAs[+T, S](delegate: GenericResponseAs[T, S with WebSockets])
    extends ResponseAsDelegate[T, S with WebSockets] {
  def map[T2](f: T => T2): WebSocketStreamResponseAs[T2, S] =
    WebSocketStreamResponseAs[T2, S](delegate.mapWithMetadata { case (t, _) => f(t) })
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): WebSocketStreamResponseAs[T2, S] =
    WebSocketStreamResponseAs[T2, S](delegate.mapWithMetadata(f))

  def showAs(s: String): WebSocketStreamResponseAs[T, S] = new WebSocketStreamResponseAs[T, S](delegate.showAs(s))
}

//

/** A wrapper around a ResponseAs to supplement it with a condition on the response metadata.
  *
  * Used in [[SttpApi.fromMetadata()]] to condition the response handler upon the response metadata: status code,
  * headers, etc.
  *
  * @tparam R
  *   The type of response
  */
case class ConditionalResponseAs[+R](condition: ResponseMetadata => Boolean, responseAs: R) {
  def map[R2](f: R => R2): ConditionalResponseAs[R2] = ConditionalResponseAs(condition, f(responseAs))
}

//

/** Generic description of how the response to a [[GenericRequest]] should be handled. To set on a request, should be
  * wrapped with an appropriate subtype of [[ResponseAsDelegate]], depending on the `R` capabilities.
  *
  * @tparam T
  *   Target type as which the response will be read.
  * @tparam R
  *   The backend capabilities required by the response description. This might be `Any` (no requirements), [[Effect]]
  *   (the backend must support the given effect type), [[Streams]] (the ability to send and receive streaming bodies)
  *   or [[WebSockets]] (the ability to handle websocket requests).
  */
sealed trait GenericResponseAs[+T, -R] {
  def map[T2](f: T => T2): GenericResponseAs[T2, R] = mapWithMetadata { case (t, _) => f(t) }
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): GenericResponseAs[T2, R] =
    MappedResponseAs[T, T2, R](this, f, None)

  def show: String
  def showAs(s: String): GenericResponseAs[T, R] = MappedResponseAs[T, T, R](this, (t, _) => t, Some(s))
}

object GenericResponseAs {
  private[client4] def parseParams(s: String, charset: String): Seq[(String, String)] =
    s.split("&")
      .toList
      .flatMap(kv =>
        kv.split("=", 2) match {
          case Array(k, v) =>
            Some((Rfc3986.decode()(k, charset), Rfc3986.decode()(v, charset)))
          case _ => None
        }
      )

  def isWebSocket(ra: GenericResponseAs[_, _]): Boolean =
    ra match {
      case _: GenericWebSocketResponseAs[_, _] => true
      case ResponseAsFromMetadata(conditions, default) =>
        conditions.exists(c => isWebSocket(c.responseAs)) || isWebSocket(default)
      case MappedResponseAs(raw, _, _) => isWebSocket(raw)
      case ResponseAsBoth(l, r)        => isWebSocket(l) || isWebSocket(r)
      case _                           => false
    }
}

case object IgnoreResponse extends GenericResponseAs[Unit, Any] {
  override def show: String = "ignore"
}
case object ResponseAsByteArray extends GenericResponseAs[Array[Byte], Any] {
  override def show: String = "as byte array"
}

// Path-dependent types are not supported in constructor arguments or the extends clause. Thus we cannot express the
// fact that `BinaryStream =:= s.BinaryStream`. We have to rely on correct construction via the companion object and
// perform typecasts when the request is deconstructed.
case class ResponseAsStream[F[_], T, Stream, S] private (s: Streams[S], f: (Stream, ResponseMetadata) => F[T])
    extends GenericResponseAs[T, S with Effect[F]] {
  override def show: String = "as stream"
}
object ResponseAsStream {
  def apply[F[_], T, S](s: Streams[S])(
      f: (s.BinaryStream, ResponseMetadata) => F[T]
  ): GenericResponseAs[T, S with Effect[F]] =
    new ResponseAsStream(s, f)
}

case class ResponseAsStreamUnsafe[BinaryStream, S] private (s: Streams[S]) extends GenericResponseAs[BinaryStream, S] {
  override def show: String = "as stream unsafe"
}
object ResponseAsStreamUnsafe {
  def apply[S](s: Streams[S]): GenericResponseAs[s.BinaryStream, S] = new ResponseAsStreamUnsafe(s)
}

case class ResponseAsFile(output: SttpFile) extends GenericResponseAs[SttpFile, Any] {
  override def show: String = s"as file: ${output.name}"
}

sealed trait GenericWebSocketResponseAs[T, -R] extends GenericResponseAs[T, R]
case class ResponseAsWebSocket[F[_], T](f: (WebSocket[F], ResponseMetadata) => F[T])
    extends GenericWebSocketResponseAs[T, WebSockets with Effect[F]] {
  override def show: String = "as web socket"
}
case class ResponseAsWebSocketUnsafe[F[_]]()
    extends GenericWebSocketResponseAs[WebSocket[F], WebSockets with Effect[F]] {
  override def show: String = "as web socket unsafe"
}
case class ResponseAsWebSocketStream[S, Pipe[_, _]](s: Streams[S], p: Pipe[WebSocketFrame.Data[_], WebSocketFrame])
    extends GenericWebSocketResponseAs[Unit, S with WebSockets] {
  override def show: String = "as web socket stream"
}

case class ResponseAsFromMetadata[T, R](
    conditions: List[ConditionalResponseAs[GenericResponseAs[T, R]]],
    default: GenericResponseAs[T, R]
) extends GenericResponseAs[T, R] {
  def apply(meta: ResponseMetadata): GenericResponseAs[T, R] =
    conditions.find(mapping => mapping.condition(meta)).map(_.responseAs).getOrElse(default)
  override def show: String = s"either(${(default.show :: conditions.map(_.responseAs.show)).mkString(", ")})"
}

case class MappedResponseAs[T, T2, R](
    raw: GenericResponseAs[T, R],
    g: (T, ResponseMetadata) => T2,
    showAs: Option[String]
) extends GenericResponseAs[T2, R] {
  override def mapWithMetadata[T3](f: (T2, ResponseMetadata) => T3): GenericResponseAs[T3, R] =
    MappedResponseAs[T, T3, R](raw, (t, h) => f(g(t, h), h), showAs.map(s => s"mapped($s)"))
  override def showAs(s: String): GenericResponseAs[T2, R] = this.copy(showAs = Some(s))

  override def show: String = showAs.getOrElse(s"mapped(${raw.show})")
}

case class ResponseAsBoth[A, B, R](l: GenericResponseAs[A, R], r: GenericResponseAs[B, Any])
    extends GenericResponseAs[(A, Option[B]), R] {
  override def show: String = s"(${l.show}, optionally ${r.show})"
}
