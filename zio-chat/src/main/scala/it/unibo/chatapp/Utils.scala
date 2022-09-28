package it.unibo.chatapp

import zio.{IO, Task, UIO, URIO, ZIO}

import java.io.{File, IOException}
import scala.io.BufferedSource

object Utils:
  def openFile(path: String): Task[BufferedSource] =
    ZIO.attempt(scala.io.Source.fromResource(path))

  def closeFile(source: BufferedSource): UIO[Unit] =
    ZIO.attempt(source.close()).orDie

  def withFile[A](path: String)(use: BufferedSource => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(openFile(path))(closeFile)(use)
