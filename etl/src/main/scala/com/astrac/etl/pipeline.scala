package com.footfall
package etl

import akka.actor._
import akka.pattern.pipe
import scala.concurrent.{duration, Future}
import ETLPattern._
import com.footfall.etl.ETLTracker.{TotalRecordsCount, Snapshot, ETLReportable}
import com.footfall.etl.ETLPattern.RegisterLoader
import com.footfall.etl.ETLPattern.Load
import scala.util.{Try, Failure, Success}
import com.footfall.etl.ETLPattern.LoaderAvailable
import com.footfall.etl.ETLPattern.ExtractedEntity
import com.footfall.etl.ETLPattern.LoaderError
import scala.reflect.ClassTag

trait Extractor[S] {
  this: Actor =>

  def nextBatch: Try[Iterable[S]]
  def totalCount: Option[Long] = None
}

trait Transformer[S, T] {
  def transform(src: S): Future[T]
}

trait Loader[S, T] {
  this: Actor =>

  def load(src: S, tgt: T): Future[Unit]
}

abstract class ExtractActor[S] extends Actor with ActorLogging with Extractor[S] {
  def receive: Receive = {
    case msg @ Extract =>
      log.debug("Extractor - Starting a new batch")
      nextBatch match {
        case Success(data) =>
          log.debug("Extractor - Batch ready")
          self.forward(msg)
          context.become(extracting(data.iterator))
        case Failure(ex) =>
          log.debug("Extractor - Failure while retrieving batch")
          sender ! Status.Failure(ex)
      }
    case GetExtractCount =>
      log.debug("Extractor - Counting available records")
      sender ! ExtractCount(totalCount)
  }

  def extracting(data: Iterator[S]): Receive = {
    case Extract =>
      log.debug(s"Extracting data (data available: ${data.hasNext})")
      if (!data.hasNext) sender ! Status.Failure(NoMoreData)
      else {
        val res = data.next()
        sender ! ExtractedEntity(res)
        if (!data.hasNext) {
          log.debug("Extractor - Batch finished!")
          context.become(receive)
        }
      }
  }
}

abstract class LoadActor[S, T](implicit evs: ClassTag[S], evt: ClassTag[T]) extends Actor with ActorLogging with Loader[S, T] {
  implicit val ec = context.dispatcher
  def coordinator: ActorRef

  override def preStart() {
    coordinator ! RegisterLoader(self)
    coordinator ! LoaderAvailable(self)
  }

  def receive = {
    case DataAvailable =>
      log.debug("Loader - Data available")
      coordinator ! LoaderAvailable(self)
    case Load(src: S, res: T) =>
      log.debug(s"Loader - Loading entry ($src, $res)")
      val f = load(src, res)
      f onComplete { _ => coordinator ! LoaderAvailable(self) }
      f map(_ => Loaded) recover { case ex => LoaderError(src, res, ex) } pipeTo sender
    case NoMoreData =>
      context.stop(self)
  }
}



object ETLPattern {
  sealed trait Message
  // Extractor messages
  case object Extract extends Message
  case object GetExtractCount extends Message

  // Extractor responses
  case class ExtractCount(total: Option[Long]) extends Message
  case class ExtractedEntity[S](data: S) extends Message
  case object NoMoreData extends Throwable

  // Transformer messages
  case class Transform[S](src: S)

  // Transformer responses
  case class Transformed[S, T](src: S, res: T)
  case class CannotTransform[S](src: S) extends Throwable

  // Loader received messages
  case class Load[S, T](src: S, res: T)
  case object DataAvailable

  // Loader sent messages
  case class LoaderAvailable(loader: ActorRef)
  case class RegisterLoader(loader: ActorRef)
  case class LoaderError[S, T](src: S, res: T, ex: Throwable) extends Throwable

  // Loader responses
  case object Loaded
  case object CannotLoad extends Throwable

  // General errors
  case class InvalidStateError(message: String) extends Throwable
}
