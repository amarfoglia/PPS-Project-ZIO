package it.unibo.zio.error

import it.unibo.zio.error.CatchDefects.Sandbox
import zio.*
import java.io.IOException

object CatchDefects {

  object Sandbox {

    val effect: IO[String, String] =
      ZIO.succeed("primary result") *> ZIO.fail("Oh uh!")

    val a: IO[Cause[String], String] = effect.sandbox

    val b: IO[Cause[String], String] = a.catchSome { case Cause.Fail(_, _) =>
      ZIO.succeed("...")
    }
    val c: IO[String, String] = b.unsandbox
  }

  object DefectSample {
    def divide(a: Int, b: Int): UIO[Int] = ZIO.succeed(a / b)
  }

  object Conversion {
    def readFile(file: String): IO[IOException, String] = ???
    lazy val result: UIO[String]                        = readFile("data.txt").orDie
  }
}
