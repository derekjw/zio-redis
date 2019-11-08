package zio.redis.serialization

import java.nio.charset.Charset

import zio.Chunk

import scala.util.control.NonFatal

case class SerializationFailure(message: String, cause: Option[Throwable]) extends RuntimeException(message, cause.orNull)

trait Read[A] {
  def apply(chunk: Chunk[Byte]): Read.Result[A]
}

object Read {
  type Result[A] = Either[SerializationFailure, A]

  @inline
  def apply[A](chunk: Chunk[Byte])(implicit read: Read[A]): Result[A] = read(chunk)

  implicit val readString: Read[String] = {
    val charset = Charset.forName("UTF8")

    { chunk =>
      try {
        Right(new String(chunk.toArray, charset))
      } catch {
        case NonFatal(e) => Left(SerializationFailure("Failed to parse as string", Some(e)))
      }
    }
  }
}
