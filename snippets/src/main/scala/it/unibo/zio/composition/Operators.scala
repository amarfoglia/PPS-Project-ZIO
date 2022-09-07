package it.unibo.zio.composition

import java.io.IOException
import scala.collection.immutable.AbstractSeq
import scala.language.postfixOps

object Operators {
  import zio._

  val zippedWith: Task[Unit] = Random.nextIntBounded(10).zipWith(Random.nextIntBounded(10)) { (a, b) =>
      println(s"$a + $b = ${a + b}")
  }

  val zipped: ZIO[Any, Nothing, (String, Int)] =
    ZIO.succeed("1") zip ZIO.succeed(4)

  val zippedRight: ZIO[Any, IOException, String] =
    Console.printLine("What is your name?") *> Console.readLine

  val zippedLeft: ZIO[Any, IOException, Unit] =
    Console.printLine("What is your name?") <* Console.readLine

  val printNumbersByForeach: ZIO[Any, IOException, Seq[Unit]] = ZIO.foreach(1 to 10) {
      n => Console.printLine(n.toString)
    }

  val printNumberByCollectAll: ZIO[Any, IOException, Seq[Unit]] = ZIO.collectAll {
    (1 to 10).map(n => Console.printLine(n))
  }
}