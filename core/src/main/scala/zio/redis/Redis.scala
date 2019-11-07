package zio.redis

import zio.ZIO

trait Redis {
  val redis: Redis.Service[Any]
}

object Redis extends commands.Basic {
  trait Service[R] {
    def ping: ZIO[R, Nothing, Unit]
  }
}
