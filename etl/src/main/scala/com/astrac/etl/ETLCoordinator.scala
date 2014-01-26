package com.footfall.etl

import akka.actor._
import akka.util._
import akka.pattern.ask
import scala.collection.mutable
import scala.concurrent.Future
import com.footfall.etl.ETLPattern._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

abstract class ETLCoordinator(timeout: FiniteDuration) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher
  implicit val t: Timeout = Timeout(timeout)

  type SrcType
  type ResType
  def extractor: ActorRef
  def transformer: Transformer[SrcType, ResType]
  val loaders = mutable.Set.empty[ActorRef]
  val trackers = mutable.Set.empty[ActorRef]

  lazy val totalRecords: Future[Option[Long]] =
    (extractor ? GetExtractCount) map { case ExtractCount(total) => total }

  def receive: Receive = {
    case RegisterLoader(loader) =>
      log.debug(s"Coordinator - Registering loader: $loader")
      context.watch(loader)
      loaders += loader
    case Terminated(loader) =>
      log.debug(s"Coordinator - Loader terminated: $loader")
      loaders -= loader
      if (loaders.isEmpty) {
        log.debug("Coordinator - Data finished, shutting down")
        context.stop(self)
      }
    case msg @ LoaderAvailable(loader) =>
      log.debug(s"Coordinator - Loader available: $loader")

      val extractFuture = (extractor ? Extract).mapTo[ExtractedEntity[SrcType]]
      def transformFuture(src: SrcType) = transformer.transform(src)
      def loadFuture(src: SrcType, res: ResType) = loader ? Load(src, res)

      val f = for {
        ExtractedEntity(src) <- extractFuture
        tr <- transformFuture(src)
        ld <- loadFuture(src, tr) if ld == ETLPattern.Loaded
      } yield (src, tr)

      f.onComplete {
        case Success((src, tr)) =>
          log.debug(s"Coordinator - Successful ETL $tr")
          trackers.foreach(_ ! ETLTracker.Loaded(src, tr))
        case Failure(NoMoreData) =>
          log.debug("Coordinator - Data finished, disconnecting loader " + loader)
          loader ! PoisonPill
        case Failure(CannotTransform(src)) =>
          log.debug(s"Coordinator - Cannot transform $src")
          self.forward(msg)
          trackers.foreach(_ ! ETLTracker.TransformFailure(src))
        case Failure(LoaderError(src, res, ex)) =>
          log.error(ex, "Coordinator - Loader error")
          trackers.foreach(_ ! ETLTracker.LoadFailure(src, res, ex))
        case Failure(ex) =>
          log.error(ex, "Coordinator - Unknown ETL error")
          if (!extractFuture.isCompleted) trackers.foreach(_ ! ETLTracker.ExtractFailure)
          else trackers.foreach(_ ! ETLTracker.UnknownFailure(ex))
          self.forward(msg)
      }
    case ETLTracker.RegisterTracker(tracker) =>
      log.debug(s"Coordinator - New tracker: $tracker")
      trackers += tracker
      totalRecords.onComplete {
        case Success(records) => tracker ! ETLTracker.TotalRecordsCount(records)
        case Failure(ex) => tracker ! ETLTracker.TotalRecordsCount(None)
      }
  }
}
