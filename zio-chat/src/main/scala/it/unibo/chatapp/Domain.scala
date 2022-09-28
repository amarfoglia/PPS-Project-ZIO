package it.unibo.chatapp

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.util.UUID

object Domain:
  case class User(id: UUID, name: String)
  
  object User {
    implicit val decoder: JsonDecoder[User] =
      DeriveJsonDecoder.gen[User]

    implicit val encoder: JsonEncoder[User] =
      DeriveJsonEncoder.gen[User]
  }

