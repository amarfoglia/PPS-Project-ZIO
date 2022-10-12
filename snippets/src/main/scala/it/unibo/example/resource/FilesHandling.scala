package it.unibo.example.resource

import zio.*

import java.io.{File, IOException}

object FilesHandling {
  def openFile(name: String): IO[IOException, File]         = ???
  def closeFile(file: File): UIO[Unit]                      = ???
  def analyze(weatherData: File, results: File): Task[Unit] = ???

  def file(name: String): ZIO[Scope, Throwable, File] =
    ZIO.acquireRelease(openFile(name))(closeFile)

  lazy val parallelAcquire: ZIO[Scope, Throwable, (File, File)] =
    file("file1.txt").zipPar(file("file2.txt"))

  def analyzeData(files: ZIO[Any, Nothing, (File, File)]): Task[Unit] =
    ZIO.scoped {
      files.flatMap { case (data1, data2) =>
        analyze(data1, data2)
      }
    }
}
