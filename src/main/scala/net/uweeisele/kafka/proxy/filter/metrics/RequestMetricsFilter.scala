package net.uweeisele.kafka.proxy.filter.metrics

import com.typesafe.scalalogging.LazyLogging
import io.micrometer.core.instrument.{Counter, MeterRegistry, Timer}
import net.uweeisele.kafka.proxy.filter.{RequestFilter, ResponseFilter}
import net.uweeisele.kafka.proxy.network.RequestChannel
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.ApiKeys

import java.io.Closeable
import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS, SECONDS}
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.language.postfixOps;

class RequestMetricsFilter(exposeListeners: Seq[ListenerName],
                           targetListeners: Seq[ListenerName],
                           meterRegistry: MeterRegistry,
                           prefix: String = "kafka") extends RequestFilter with ResponseFilter with Closeable with LazyLogging {

  private val requestCounters = ApiKeys.values().flatMap(apiKey => exposeListeners.map(listener => (apiKey, listener))).map {
    case (apiKey: ApiKeys, listener: ListenerName) =>
      (apiKey, listener) -> Counter.builder(s"$prefix.requests")
        .tag("apiKeyId", apiKey.id.toString)
        .tag("apiKeyName", apiKey.name)
        .tag("exposeListenerName", listener.value())
        .register(meterRegistry)
    } toMap

  private val responseCounters = ApiKeys.values().flatMap(apiKey => exposeListeners.flatMap(el => targetListeners.map(tl => (apiKey, el, tl)))).map {
    case (apiKey: ApiKeys, exposedListener: ListenerName, targetListener: ListenerName) =>
      (apiKey, exposedListener, targetListener) -> Counter.builder(s"$prefix.responses")
        .tag("apiKeyId", apiKey.id.toString)
        .tag("apiKeyName", apiKey.name)
        .tag("exposeListenerName", exposedListener.value())
        .tag("targetListenerName", targetListener.value())
        .register(meterRegistry)
      } toMap

  private val responseDurations = ApiKeys.values().flatMap(apiKey => exposeListeners.flatMap(el => targetListeners.map(tl => (apiKey, el, tl)))).map {
    case (apiKey: ApiKeys, exposedListener: ListenerName, targetListener: ListenerName) =>
      (apiKey, exposedListener, targetListener) -> Timer.builder(s"$prefix.responses.duration")
        .tag("apiKeyId", apiKey.id.toString)
        .tag("apiKeyName", apiKey.name)
        .tag("exposeListenerName", exposedListener.value())
        .tag("targetListenerName", targetListener.value())
        .distributionStatisticExpiry(Duration.create(300, SECONDS).toJava)
        .publishPercentileHistogram()
        .register(meterRegistry)
      } toMap

  override def handle(request: RequestChannel.Request): Unit = {
    request.context.variables(s"${getClass.getName}:responses.duration") = System.currentTimeMillis
    requestCounters.get((request.header.apiKey, request.context.listenerNameRef)) match {
      case Some(counter) => counter.increment()
      case None => logger.info(s"ApiKey ${request.header.apiKey.name} or listener ${request.context.listenerNameRef.value} is unknown.")
    }
  }

  override def handle(response: RequestChannel.SendResponse): Unit = {
    responseCounters.get((response.response.apiKey, response.request.context.listenerNameRef, response.forwardContext.listenerNameRef)) match {
      case Some(counter) => counter.increment()
      case None => logger.info(
        s"ApiKey ${response.response.apiKey.name} or " +
          s"expose listener ${response.request.context.listenerNameRef.value} " +
          s"or target listener ${response.forwardContext.listenerNameRef.value} is unknown.")
    }
    responseDurations.get((response.response.apiKey, response.request.context.listenerNameRef, response.forwardContext.listenerNameRef)) match {
      case Some(timer) => timer.record(measureDuration(response.request).toJava)
      case None => logger.info(
        s"ApiKey ${response.response.apiKey.name} or " +
          s"expose listener ${response.request.context.listenerNameRef.value} " +
          s"or target listener ${response.forwardContext.listenerNameRef.value} is unknown.")
    }
  }

  override def close(): Unit = {
    requestCounters.foreach { case (_, counter) =>
      meterRegistry.remove(counter)
    }
    responseCounters.foreach { case (_, counter) =>
      meterRegistry.remove(counter)
    }
    responseDurations.foreach { case (_, counter) =>
      meterRegistry.remove(counter)
    }
  }

  private def measureDuration(request: RequestChannel.Request): FiniteDuration = {
    request.context.variables.get(s"${getClass.getName}:responses.duration") match {
      case Some(startMs: Long) => (System.currentTimeMillis - startMs, MILLISECONDS)
      case _ =>
        logger.warn(s"Something went wrong! Request does not contain variable '${this.getClass.getName}:responses.duration'.")
        Duration.Zero
    }
  }
}