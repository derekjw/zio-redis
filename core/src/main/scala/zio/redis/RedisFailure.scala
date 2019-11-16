package zio.redis

abstract class RedisFailure(message: String, cause: Option[Throwable]) extends RuntimeException(message, cause.orNull)

abstract class RedisClientFailure(message: String, cause: Option[Throwable]) extends RedisFailure(message, cause)

case class ConnectionFailure(message: String, cause: Option[Throwable] = None) extends RedisClientFailure(message, cause)

case class UnexpectedResponse(message: String, cause: Option[Throwable] = None) extends RedisClientFailure(message, cause)

case class SerializationFailure(message: String, cause: Option[Throwable] = None) extends RedisFailure(message, cause)
