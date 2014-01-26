package com.footfall
package etl

import com.typesafe.config.{ConfigFactory, Config}
import scala.concurrent.duration._
import scala.concurrent.duration.DurationConversions._
import java.util.concurrent.TimeUnit

class Settings(config: Config) {
  config.checkValid(ConfigFactory.defaultReference())

  def optionalString(key: String): Option[String] = config.getString(key) match {
    case "" => None
    case str => Some(str)
  }

  val trackerWebEnabled = config.getBoolean("etl.tracker.web.enabled")
  val trackerWebHost = config.getString("etl.tracker.web.host")
  val trackerWebPort = config.getInt("etl.tracker.web.port")

  val trackerLogLoaded = optionalString("etl.tracker.log.loaded")
  val trackerLogTransformErrors = optionalString("etl.tracker.log.transform_errors")
  val trackerLogLoadErrors = optionalString("etl.tracker.log.load_errors")
  val trackerLogUnknownErrors = optionalString("etl.tracker.log.unknown_errors")

  val loaderInstances = config.getInt("etl.loader.instances")

  val etlTimeout = FiniteDuration(config.getMilliseconds("etl.timeout"), TimeUnit.MILLISECONDS)
}
