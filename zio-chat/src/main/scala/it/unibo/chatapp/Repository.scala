package it.unibo.chatapp

import it.unibo.chatapp.Domain.User
import zio.{Console, IO, Ref, Task, UIO, ZIO, ZLayer, Random}

import java.util.UUID

object Repository:

  trait UserRepository:
    def create(user: User): Task[User]
    def all: Task[List[User]]
    def get(id: UUID): Task[Option[User]]

  case class UserRepositoryInMemory(ref: Ref[Map[UUID, User]]) extends UserRepository:
    override def create(user: User): Task[User] = for {
      _ <- ref.update(_ + (user.id -> user))
      _ <- Console.printLine(ref).fork
    } yield user

    override def get(id: UUID): Task[Option[User]] =
      ref.get.map(_.get(id))

    override def all: Task[List[User]] =
      ref.get.map(_.values.toList)

  case class Config(path: String)

  import Utils.withFile
  import zio.json.DecoderOps

  object UserRepository:
    val inMemory: ZLayer[Config, Throwable, UserRepositoryInMemory] = ZLayer.scoped {
      for
        config  <- ZIO.service[Config]
        content <- withFile(config.path)(b => ZIO.succeed(b.getLines.mkString))
        users   <- ZIO.from(content.fromJson[List[User]])
                      .orElseFail(new RuntimeException("Could not parse users"))
        init    <- ZIO.succeed(users.zipWithIndex.map { case(u, _) => (u.id, u) }.toMap)
        ref     <- Ref.make(init)
        _       <- Console.printLine(ref).fork
      yield UserRepositoryInMemory(ref)
    }