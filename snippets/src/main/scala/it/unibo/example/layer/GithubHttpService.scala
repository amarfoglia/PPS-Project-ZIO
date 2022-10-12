package it.unibo.zio.layer

import zio._

object Main extends ZIOAppDefault {
  import HttpLayer.HttpLive
  import GithubLayer.GithubLive
  import BusinessLogicLayer.{BusinessLogic, BusinessLogicLive}
  import HttpLayer.Config.HttpConfig

  val run =
    ZIO
      .serviceWithZIO[BusinessLogic](_.run)
      .provide(
        BusinessLogicLive.layer,
        GithubLive.layer,
        HttpLive.layer,
        HttpConfig.layer
      )
}
