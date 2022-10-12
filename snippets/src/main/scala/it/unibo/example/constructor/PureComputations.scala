package it.unibo.example.constructor

import zio._

import scala.io.StdIn
import scala.language.postfixOps

object PureComputations {
  def currentTime(): Long = java.lang.System.currentTimeMillis()

  lazy val currentTimeZIO: ZIO[Any, Nothing, Long] =
    ZIO.succeed(currentTime())

  def eitherToZIO[E, A](either: Either[E, A]): ZIO[Any, E, A] =
    either.fold(e => ZIO.fail(e), a => ZIO.succeed(a))

  def headToZIO[A](list: List[A]): ZIO[Any, None.type, A] =
    list match {
      case h :: _ => ZIO.succeed(h)
      case Nil    => ZIO.fail(None)
    }
}
