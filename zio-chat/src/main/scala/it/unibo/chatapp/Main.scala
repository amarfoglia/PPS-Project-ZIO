package it.unibo.chatapp

import it.unibo.chatapp.Auth.{AuthService, AuthServiceLive}
import it.unibo.chatapp.Repository.{Config, UserRepository, UserRepositoryInMemory}
import zio.{Console, IO, ZIO, ZIOAppDefault, ZLayer}

import java.io.IOException

object Main extends ZIOAppDefault:
  val run =
    ZIO.service[AuthService]
      .provide(
        AuthService.live,
        UserRepository.inMemory,
        ZLayer.succeed(Config("users.json"))
      ) flatMap(_.signup("Luca Bianchi")) 
