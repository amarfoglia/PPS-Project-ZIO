package it.unibo.example.error

import zio._

object CatchCause {

  val exceptionalEffect: IO[String, Nothing] =
    ZIO.succeed(5) *> ZIO.fail("Oh uh!")

  val catchAll = exceptionalEffect.catchAllCause {
    case Cause.Empty =>
      ZIO.debug("no error caught")
    case Cause.Fail(value, _) =>
      ZIO.debug(s"a failure caught: $value")
    case Cause.Die(value, _) =>
      ZIO.debug(s"a defect caught: $value")
    case Cause.Interrupt(fiberId, _) =>
      ZIO.debug(s"a fiber interruption caught with the fiber id: $fiberId")
    case Cause.Stackless(cause: Cause.Die, _) =>
      ZIO.debug(s"a stackless defect caught: ${cause.value}")
    case Cause.Stackless(cause: Cause[_], _) =>
      ZIO.debug(s"an unknown stackless defect caught: $cause")
    case Cause.Then(_, _) =>
      ZIO.debug(s"two consequence causes caught")
    case Cause.Both(_, _) =>
      ZIO.debug(s"two parallel causes caught")
  }
}
