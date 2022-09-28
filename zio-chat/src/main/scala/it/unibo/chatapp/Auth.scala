package it.unibo.chatapp

import it.unibo.chatapp.Domain.User
import it.unibo.chatapp.Repository.UserRepository
import zio.{Task, ZLayer, URLayer, Random}

object Auth:
  trait AuthService:
    def signup(userName: String): Task[User]

  case class AuthServiceLive(repository: UserRepository) extends AuthService:
    override def signup(userName: String): Task[User] = for {
      uuid <- Random.nextUUID
      user = User(uuid, userName)
      _    <- repository.create(user)
    } yield user

  object AuthService:
    val live: URLayer[UserRepository, AuthServiceLive] =
      ZLayer.fromFunction(AuthServiceLive(_))