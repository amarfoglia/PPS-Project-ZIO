package it.unibo.zio.resource

import zio._

object ScopedFork {

  val heartbeat: ZIO[Scope, Nothing, Fiber[Nothing, Unit]] =
    Console.printLine(".").orDie.delay(1.second).forever.forkScoped

  lazy val myProgramLogic: ZIO[Any, Nothing, Unit] = ???

  val program: URIO[Scope, Unit] = ZIO.scoped {
    for {
      _ <- heartbeat
      _ <- myProgramLogic
    } yield ()
  }
}
