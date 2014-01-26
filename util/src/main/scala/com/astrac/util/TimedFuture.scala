package com.astrac.util

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object TimedFuture {
  case class TimedResult[T](result: T, runtime: FiniteDuration)
}

trait TimedFuture {
  import TimedFuture._

  def timedFuture[T](f: => T)(implicit ec: ExecutionContext): Future[TimedResult[T]] = {
    val time = System.currentTimeMillis()
    val p = Promise[TimedResult[T]]()

    def elapsedTime = (System.currentTimeMillis() - time).millis

    Future(f).onComplete {
      case Success(res) => p.success(TimedResult(res, elapsedTime))
      case Failure(ex) => p.failure(ex)
    }

    p.future
  }
}