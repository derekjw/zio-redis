package zio.redis.protocol

import zio.Chunk

trait RedisType

case class RedisString(value: String) extends RedisType
case class RedisInteger(value: Long) extends RedisType
case class RedisBulk(value: Option[Chunk[Byte]]) extends RedisType
object RedisBulk {
  val notFound = RedisBulk(None)
  val empty = RedisBulk(Some(Chunk.empty))
}
case class RedisMulti(value: Option[Chunk[RedisType]]) extends RedisType
object RedisMulti {
  val notFound = RedisMulti(None)
  val empty = RedisMulti(Some(Chunk.empty))
}
case class RedisError(value: String) extends RedisType
