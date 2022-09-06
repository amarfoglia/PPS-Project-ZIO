package it.unibo.zio.composition

import zio._

object SequentialComposition {
  import scala.io.StdIn

  sealed trait Console[+L]:
    def readLine: Task[L]
    def printLine[L1 >: L](line: L1): Task[Unit]

  object Console:
    lazy val stdConsole = new Console[String]:
      override val readLine: Task[String] =
        ZIO.attempt(StdIn.readLine())

      override def printLine[String](line: String): Task[Unit] =
        ZIO.attempt(println(line))

  import Console.stdConsole

  val byFlatMap: Task[Unit] =
    stdConsole.readLine.flatMap(line => stdConsole.printLine(line))

  val byForComprehension: Task[Unit] = for {
      line <- stdConsole.readLine
      _    <- stdConsole.printLine(line)
    } yield ()
}