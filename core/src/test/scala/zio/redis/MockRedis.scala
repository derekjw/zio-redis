package zio.redis

trait MockRedis extends Redis {

}

object MockRedis {
  trait Service[R] extends Redis.Service[R]
}