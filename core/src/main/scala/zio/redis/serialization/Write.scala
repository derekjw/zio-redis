package zio.redis.serialization

import java.nio.charset.Charset

import zio.Chunk

trait Write[A] {
  def apply(in: A): Chunk[Byte]
}

object Write {
  @inline
  def apply[A](in: A)(implicit write: Write[A]): Chunk[Byte] = write(in)

  implicit val writeString: Write[String] = {
    val charset = Charset.forName("UTF8")

    { str =>
      Chunk.fromArray(str.getBytes(charset))
    }
  }
}
