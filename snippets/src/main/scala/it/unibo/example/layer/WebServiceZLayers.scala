package it.unibo.example.layer

import zio.{Chunk, ZIO, ZLayer}

object Model {
  trait Issue
  final case class Comment(text: String) extends Issue
}

object BusinessLogicLayer {

  trait BusinessLogic {
    def run: ZIO[Any, Throwable, Unit]
  }

  import GithubLayer.Github
  import Model.Comment

  final case class BusinessLogicLive(github: Github) extends BusinessLogic {

    val run: ZIO[Any, Throwable, Unit] =
      for {
        issues <- github.getIssues("zio")
        comment = Comment("I am working on this!")
        _ <- ZIO.getOrFail(issues.headOption).flatMap { issue =>
          github.postComment(issue, comment)
        }
      } yield ()
  }

  object BusinessLogicLive {

    val layer: ZLayer[Github, Nothing, BusinessLogic] =
      ZLayer.fromFunction(BusinessLogicLive(_))
  }
}

object GithubLayer {
  import Model._

  trait Github {
    def getIssues(organization: String): ZIO[Any, Throwable, Chunk[Issue]]
    def postComment(issue: Issue, comment: Comment): ZIO[Any, Throwable, Unit]
  }

  import HttpLayer.Http

  final case class GithubLive(http: Http) extends Github {
    def getIssues(organization: String): ZIO[Any, Throwable, Chunk[Issue]]     = ???
    def postComment(issue: Issue, comment: Comment): ZIO[Any, Throwable, Unit] = ???
  }

  object GithubLive {

    val layer: ZLayer[Http, Nothing, Github] =
      ZLayer.fromFunction(GithubLive(_))
  }
}

object HttpLayer {

  trait Http {
    def get(url: String): ZIO[Any, Throwable, Chunk[Byte]]
    def post(url: String, body: Chunk[Byte]): ZIO[Any, Throwable, Chunk[Byte]]
  }

  object Config {
    trait HttpConfig

    object HttpConfig {
      final case class HttpConfigLive() extends HttpConfig

      val layer: ZLayer[Any, Any, HttpConfig] =
        ZLayer { ZIO.succeed(HttpConfigLive()) }
    }
  }

  import Config.HttpConfig

  final case class HttpLive(config: HttpConfig) extends Http {

    def get(url: String): ZIO[Any, Throwable, Chunk[Byte]] = ???

    def post(
      url: String,
      body: Chunk[Byte]
    ): ZIO[Any, Throwable, Chunk[Byte]]   = ???
    def start: ZIO[Any, Throwable, Unit]  = ???
    def shutdown: ZIO[Any, Nothing, Unit] = ???
  }

  object HttpLive {

    val layer: ZLayer[HttpConfig, Throwable, Http] =
      ZLayer.scoped {
        for {
          config <- ZIO.service[HttpConfig]
          http   <- ZIO.succeed(HttpLive(config))
          _      <- http.start
          _      <- ZIO.addFinalizer(http.shutdown)
        } yield http
      }
  }
}
