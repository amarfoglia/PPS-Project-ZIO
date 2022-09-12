package it.unibo.zio.error

import zio._

object MultipleErrors {

  object Combine {
    final case class ApiError(message: String) extends Exception(message)

    final case class DbError(message: String) extends Exception(message)

    trait Result

    lazy val callApi: ZIO[Any, ApiError, String] = ???
    lazy val queryDb: ZIO[Any, DbError, Int]     = ???

    lazy val combine: ZIO[Any, Exception, (String, Int)] =
      callApi.zip(queryDb)
  }

  object Parallel {

    val result: ZIO[Any, ::[String], Nothing] =
      (ZIO.fail("Oh uh!") <&> ZIO.fail("Oh Error!")).parallelErrors
  }

  object MapError {
    final case class InsufficientPermission(user: String, operation: String)
    final case class FileIsLocked(file: String)
    def shareDocument(doc: String): ZIO[Any, InsufficientPermission, Unit]      = ???
    def moveDocument(doc: String, folder: String): ZIO[Any, FileIsLocked, Unit] = ???

    type DocumentError = Either[InsufficientPermission, FileIsLocked]

    lazy val result2: ZIO[Any, DocumentError, Unit] =
      shareDocument("347823")
        .mapError(Left(_))
        .zip(moveDocument("347823", "/temp/").mapError(Right(_)))
        .unit

  }
}
