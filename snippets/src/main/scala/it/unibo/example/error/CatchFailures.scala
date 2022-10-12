package it.unibo.zio.error

import zio.*

object CatchFailures {
  sealed trait AgeValidationException       extends Exception
  case class NegativeAgeException(age: Int) extends AgeValidationException
  case class IllegalAgeException(age: Int)  extends AgeValidationException

  def validate(age: Int): ZIO[Any, AgeValidationException, Int] =
    if (age < 0)
      ZIO.fail(NegativeAgeException(age))
    else if (age < 18)
      ZIO.fail(IllegalAgeException(age))
    else ZIO.succeed(age)

  val result: UIO[Int] =
    validate(10)
      .catchAll {
        case NegativeAgeException(age) =>
          ZIO.debug(s"negative age: $age").as(-1)
        case IllegalAgeException(age) =>
          ZIO.debug(s"illegal age: $age").as(-1)
      }
}
