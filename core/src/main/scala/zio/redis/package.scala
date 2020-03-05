package zio

package object redis {
  type Redis = Has[Redis.Service]
}
