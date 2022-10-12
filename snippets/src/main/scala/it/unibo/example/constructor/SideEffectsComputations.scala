package it.unibo.example.constructors

import zio.{Task, ZIO}

import scala.io.StdIn

object SideEffectsComputations {

  object AttemptZio {

    val echo: Unit = {
      val line = StdIn.readLine()
      println(line)
    }

    val readLine: Task[String] =
      ZIO.attempt(StdIn.readLine())

    def printLine(line: String): Task[Unit] = ZIO.attempt(println(line))

    val echoZIO: Task[Unit] = for {
      line <- readLine
      _    <- printLine(line)
    } yield ()
  }

  object AsyncZio {
    def getPostByIdAsync(id: Int)(cb: Option[String] => Unit): Unit = ???

    def getPostById(id: Int): ZIO[Any, None.type, String] =
      ZIO.async { cb =>
        getPostByIdAsync(id) {
          case Some(name) => cb(ZIO.succeed(name))
          case None       => cb(ZIO.fail(None))
        }
      }
  }

  object FutureZio {
    import scala.concurrent.{ExecutionContext, Future}
    type Query
    type Result

    def doQuery(query: Query)(implicit ec: ExecutionContext): Future[Result] = ???

    def doQueryZio(query: Query): ZIO[Any, Throwable, Result] =
      ZIO.fromFuture(ec => doQuery(query)(ec))
  }
}
