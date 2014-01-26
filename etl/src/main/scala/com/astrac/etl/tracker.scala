package com.footfall
package etl

import akka.actor._
import spray.json.{JsNumber, JsValue, JsonWriter, DefaultJsonProtocol}
import spray.routing.HttpServiceActor
import akka.util.Timeout
import spray.http.{HttpResponse, ContentTypes, HttpEntity}
import ETLTracker.{Snapshot, ETLReportable}
import akka.pattern._
import scala.concurrent.duration._

object ETLTracker {
  sealed trait Message
  case object Extracted extends Message
  case object Transformed extends Message
  case class TotalRecordsCount(count: Option[Long]) extends Message

  case class RegisterTracker(tracker: ActorRef)

  sealed trait ETLReportable
  case class Loaded[S, T](source: S, transformed: T) extends Message with ETLReportable
  case object ExtractFailure extends Message with ETLReportable
  case class TransformFailure[S](source: S) extends Message with ETLReportable
  case class LoadFailure[S, T](source: S, transformed: T, ex: Throwable) extends Message with ETLReportable
  case class UnknownFailure(ex: Throwable) extends Message with ETLReportable

  case object TakeSnapshot extends Message
  case class Snapshot(extracted: Long,
                      transformed: Long,
                      loaded: Long,
                      extractFailures: Long,
                      transformFailures: Long,
                      loadFailures: Long,
                      unknownFailures: Long,
                      runtime: FiniteDuration,
                      totalRecords: Option[Long],
                      extimatedRemainingTime: Option[FiniteDuration])

  def props(coordinator: ActorRef, reporting: ETLReportable => Unit = { _=> ()}) = Props(classOf[ETLTracker], coordinator, reporting)
}

class ETLTracker(coordinator: ActorRef, reporting: ETLReportable => Unit) extends Actor with ActorLogging {
  var extracted = 0
  var transformed = 0
  var loaded = 0
  var extractFailures = 0
  var transformFailures = 0
  var loadFailures = 0
  var unknownFailures = 0
  var startTime = 0l
  var totalRecords: Option[Long] = None

  def snapshot = {
    val runtime = (System.currentTimeMillis() - startTime).millis
    val extimatedRemainingTime = totalRecords.map { total =>
      if (runtime.isFinite && runtime.toMillis > 0)
        ((total.toDouble * (runtime.toMillis.toDouble / loaded.toDouble)) - runtime.toMillis).millis
      else
        0.millis
    }

    Snapshot(extracted, transformed, loaded, extractFailures, transformFailures, loadFailures, unknownFailures, runtime, totalRecords, extimatedRemainingTime)
  }

  override def preStart() {
    context.watch(coordinator)
    coordinator ! ETLTracker.RegisterTracker(self)
    startTime = System.currentTimeMillis()
  }

  def receive: Actor.Receive = {
    case ETLTracker.TotalRecordsCount(count) =>
      log.debug(count.map(c => s"Total records to migrate: $c (tracker: $self)").getOrElse("Cannot retrieve total records"))
      totalRecords = count
    case ETLTracker.TakeSnapshot =>
      sender ! snapshot
    case msg : ETLTracker.Loaded[_, _] =>
      log.debug("ETLTracker - receive Loaded")
      extracted = extracted + 1
      transformed = transformed + 1
      loaded = loaded + 1
      reporting(msg)
    case ETLTracker.ExtractFailure =>
      log.debug("ETLTracker - receive ExtractFailure")
      extractFailures = extractFailures + 1
      reporting(ETLTracker.ExtractFailure)
    case msg: ETLTracker.TransformFailure[_] =>
      log.debug("ETLTracker - receive TransformFailure")
      extracted = extracted + 1
      transformFailures = transformFailures + 1
      reporting(msg)
    case msg: ETLTracker.LoadFailure[_, _] =>
      log.debug("ETLTracker - receive LoadFailure")
      extracted = extracted + 1
      transformed = transformed + 1
      loadFailures = loadFailures + 1
      reporting(msg)
    case msg: ETLTracker.UnknownFailure =>
      log.debug("ETLTracker - receive UnknownFailure")
      extracted = extracted + 1
      unknownFailures = unknownFailures + 1
      reporting(msg)
    case Terminated(c) =>
      log.debug("ETLTracker - receive Terminated from Coordinator")

      val elapsedTime = (System.currentTimeMillis() - startTime).milliseconds.toMinutes
      println(s"\nETLTracker :: Finished execution, total elapsed time: ${elapsedTime} minutes")
      println(s"Final statistics:\n  Total extracted: $extracted\n  Total loaded: $loaded" +
        s"\n  Transform failures: $transformFailures\n  Load failures: $loadFailures\n  Unknown failures: $unknownFailures")
      context.system.shutdown()
  }
}

object ETLJsonProtocol extends DefaultJsonProtocol {
  implicit val durationFormat = lift(new JsonWriter[FiniteDuration] {
    def write(obj: FiniteDuration): JsValue = JsNumber(obj.toMillis)
  })
  implicit val snapshotFormat = jsonFormat10(Snapshot)
}

object ETLTrackerServer {
  def props(tracker: ActorRef) = Props(classOf[ETLTrackerServer], tracker)
}

class ETLTrackerServer(tracker: ActorRef) extends HttpServiceActor {
  import ETLTracker._
  import spray.json._
  import ETLJsonProtocol._
  import spray.httpx.SprayJsonSupport._

  implicit val to = Timeout.durationToTimeout(40.seconds)
  implicit val ec = context.dispatcher

  val route =
    pathPrefix("web") {
      getFromResourceDirectory("ui/app")
    } ~
      pathPrefix("api") {
        path("status") {
          get {
            complete {
              (tracker ? TakeSnapshot).mapTo[Snapshot]
            }
          }
        }
      }

  def receive: Receive = runRoute(sealRoute(route))
}
