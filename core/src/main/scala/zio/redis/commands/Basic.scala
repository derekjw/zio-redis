package zio.redis.commands

import zio.ZIO
import zio.redis.Redis
import zio.redis.serialization.{Read, SerializationFailure, Write}

trait Basic {
  def get[A: Write, B: Read](key: A): ZIO[Redis, SerializationFailure, B] = ???
  def set[A: Write, B: Write](key: A, value: B): ZIO[Redis, Nothing, Unit] = ???
}
