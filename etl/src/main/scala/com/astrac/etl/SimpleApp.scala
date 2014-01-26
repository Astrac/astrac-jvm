package com.footfall
package etl

import akka.actor.{PoisonPill, Props, ActorRef, ActorSystem}
import java.io.{FileOutputStream, PrintStream}
import com.footfall.etl.ETLTracker._
import akka.routing.RoundRobinRouter
import akka.io.IO
import spray.can.Http
import scala.concurrent.Future
import akka.util.Timeout
import scala.concurrent.duration.FiniteDuration

trait SimpleApp {
  def system: ActorSystem
  implicit lazy val s = system

  def optionalStream(fileOpt: Option[String]): Option[PrintStream] = fileOpt.map(f => new PrintStream(new FileOutputStream(f)))
  def optionalLog(stream: Option[PrintStream], line: String) = stream.foreach(_.println(line))

  lazy val settings = new Settings(system.settings.config)
  lazy val outLoaded = optionalStream(settings.trackerLogLoaded)
  lazy val outTransformErrors = optionalStream(settings.trackerLogTransformErrors)
  lazy val outLoadErrors = optionalStream(settings.trackerLogLoadErrors)
  lazy val outUnknownErrors = optionalStream(settings.trackerLogUnknownErrors)

  def reporting(msg: ETLReportable): Unit = msg match {
    case Loaded(source, transformed) =>
      optionalLog(outLoaded, s"$source => $transformed")
    case ExtractFailure =>
      optionalLog(outUnknownErrors , "Cannot extract record!")
    case TransformFailure(source) =>
      optionalLog(outTransformErrors, source.toString)
    case LoadFailure(source, transformed, ex: Throwable) =>
      optionalLog(outLoadErrors, s"$source => $transformed -- $ex")
    case UnknownFailure(ex: Throwable) =>
      optionalLog(outUnknownErrors, ex.toString + "\n" + ex.getStackTraceString)
  }

  def coordinatorProps(pipelineTimeout: FiniteDuration): Props
  def loaderProps(coordinator: ActorRef): Props

  def main(args: Array[String]) {
    val coordinator = system.actorOf(coordinatorProps(settings.etlTimeout), "etl-coordinator")
    val tracker = system.actorOf(ETLTracker.props(coordinator, reporting), "etl-tracker")

    println(s"\nStarting ${settings.loaderInstances} loaders")
    system.actorOf(loaderProps(coordinator).withRouter(RoundRobinRouter(nrOfInstances = settings.loaderInstances)), "etl-loader")

    val trackerServer = if (settings.trackerWebEnabled) {
      val act = system.actorOf(ETLTrackerServer.props(tracker))
      IO(Http) ! Http.Bind(act, interface = settings.trackerWebHost, port = settings.trackerWebPort)

      println(s"\n\n*** ETL WebTracker bound on http://${settings.trackerWebHost}:${settings.trackerWebPort}/web/index.html **\n\n")
      Some(act)
    } else { None }
    
    implicit val ex = system.dispatcher
    Future(readLine("Press ENTER to stop the ETL process\n\n")).onComplete {
      _ => 
        coordinator ! PoisonPill
        trackerServer.foreach(_ ! PoisonPill)
    }

    system.awaitTermination()

    outLoaded.foreach(_.close())
    outTransformErrors.foreach(_.close())
    outLoadErrors.foreach(_.close())
    outUnknownErrors.foreach(_.close())
  }
}
