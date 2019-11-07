package zio.redis.serialization

import zio.Chunk

trait Write[A] {
  def apply(in: A): Chunk[Byte]
}
