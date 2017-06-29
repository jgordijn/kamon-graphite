package kamon.graphite

import java.net.InetSocketAddress

import scala.collection.JavaConverters._
import akka.actor.{Actor, ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.io.Tcp.SO.KeepAlive
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import kamon.Kamon
import kamon.metric._
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.util.ConfigTools.Syntax
import kamon.metric.instrument.{Counter, Histogram}
import kamon.util.NeedToScale
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object Graphite extends ExtensionId[GraphiteExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = Graphite
  override def createExtension(system: ExtendedActorSystem): GraphiteExtension = new GraphiteExtension()(system)
}

class GraphiteExtension(implicit system: ExtendedActorSystem) extends Kamon.Extension {
  private val log = LoggerFactory.getLogger(classOf[GraphiteExtension])
  log.info("Starting the Kamon(Graphite) extension")

  private val config = system.settings.config
  private val graphiteConfig = config.getConfig("kamon.graphite")

  private val tickInterval = Kamon.metrics.settings.tickInterval
  private val flushInterval = graphiteConfig.getFiniteDuration("flush-interval")

  private val hostname = graphiteConfig.getString("hostname")
  private val port = graphiteConfig.getInt("port")
  private val metricPrefix = graphiteConfig.getString("metric-name-prefix")
  private val retryBufferSize = graphiteConfig.getInt("write-retry-buffer-size")

  private val graphiteClient = createGraphiteClient()

  private val subscriptions = graphiteConfig.getConfig("subscriptions")
  subscriptions.firstLevelKeys.foreach { subscriptionCategory ⇒
    subscriptions.getStringList(subscriptionCategory).asScala.foreach { pattern ⇒
      Kamon.metrics.subscribe(subscriptionCategory, pattern, graphiteClient, permanently = true)
    }
  }

  private def createGraphiteClient(): ActorRef = {
    if(flushInterval < tickInterval)
      log.warn("Ignoring Graphite flush-interval. It needs to be equal or greater to the tick-interval")
    val metricsSender = system.actorOf(Props(new GraphiteClient(hostname, port, 10 seconds, 10 seconds, retryBufferSize, metricPrefix)), "kamon-graphite")
    val graphiteClient = graphiteConfig match {
      case NeedToScale(scaleTimeTo, scaleMemoryTo) ⇒
        system.actorOf(MetricScaleDecorator.props(scaleTimeTo, scaleMemoryTo, metricsSender), "graphite-metric-scale-decorator")
      case _ ⇒ metricsSender
    }

    if (flushInterval <= tickInterval) {
      // No need to buffer the metrics, let's go straight to the metrics sender.
      graphiteClient
    } else {
      system.actorOf(TickMetricSnapshotBuffer.props(flushInterval, graphiteClient), "graphite-metrics-buffer")
    }
  }

}

trait MetricPacket {
  def append(metricName: String, value: Long): MetricPacket
  def byteString(): ByteString
}

trait MetricPacking {

  private def sanitize(value: String): String =
    value.replace('/', '_').replace('.', '_')

  private def baseName(prefix: String, entity: Entity, key: MetricKey): String =
    new java.lang.StringBuilder()
      .append(prefix)
      .append(".")
      .append(entity.category)
      .append(".")
      .append(sanitize(entity.name))
      .append(".")
      .append(sanitize(key.name))
      .toString()

  private def newMetricPacket(baseName: String, timestamp: Long) = new MetricPacket {
    private val builder = new java.lang.StringBuilder()

    def append(metricName: String, value: Long): this.type = {
      this.builder
        .append(baseName)
        .append(".")
        .append(metricName)
        .append(" ")
        .append(value)
        .append(" ")
        .append(timestamp)
        .append("\n")

      this
    }

    def byteString(): ByteString = ByteString(this.builder.toString)
  }

  def packHistogram(prefix: String, entity: Entity, histogramKey: HistogramKey, snapshot: Histogram.Snapshot, timestamp: Long): ByteString = {
    newMetricPacket(baseName(prefix, entity, histogramKey), timestamp)
      .append("count", snapshot.numberOfMeasurements)
      .append("min", snapshot.min)
      .append("max", snapshot.max)
      .append("p50", snapshot.percentile(50D))
      .append("p90", snapshot.percentile(90D))
      .append("p99", snapshot.percentile(99D))
      .append("sum", snapshot.sum)
      .byteString()
  }

  def packGauge(prefix: String, entity: Entity, histogramKey: GaugeKey, snapshot: Histogram.Snapshot, timestamp: Long): ByteString = {
    newMetricPacket(baseName(prefix, entity, histogramKey), timestamp)
      .append("min", snapshot.min)
      .append("max", snapshot.max)
      .append("sum", snapshot.sum)
      .append("avg", average(snapshot))
      .byteString()
  }

  def packMinMaxCounter(prefix: String, entity: Entity, minMaxCounterKey: MinMaxCounterKey, snapshot: Histogram.Snapshot, timestamp: Long): ByteString = {
    newMetricPacket(baseName(prefix, entity, minMaxCounterKey), timestamp)
      .append("min", snapshot.min)
      .append("max", snapshot.max)
      .append("avg", average(snapshot))
      .byteString()
  }

  def packCounter(prefix: String, entity: Entity, counterKey: CounterKey, snapshot: Counter.Snapshot, timestamp: Long): ByteString = {
    newMetricPacket(baseName(prefix, entity, counterKey), timestamp)
      .append("count", snapshot.count)
      .byteString()
  }

  private def average(snapshot: Histogram.Snapshot): Long =
    if(snapshot.numberOfMeasurements > 0) snapshot.sum / snapshot.numberOfMeasurements else 0

}

class GraphiteClient(
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    connectionRetryDelay: FiniteDuration,
    writeRetryBufferSize: Int,
    metricPrefix: String)
  extends Actor with MetricPacking {

  import context.dispatcher
  private val log = LoggerFactory.getLogger(classOf[GraphiteClient])
  private var failedWrites = Seq.empty[Write]

  override def receive: Receive = disconnected

  override def preStart(): Unit = {
    self ! GraphiteClient.InitiateConnection
  }

  def disconnected: Actor.Receive = discardSnapshots orElse {
    case GraphiteClient.InitiateConnection =>
      IO(Tcp)(context.system) ! Connect(
        remoteAddress = new InetSocketAddress(host, port),
        timeout = Some(connectionTimeout),
        options = List(KeepAlive(on = true))
      )
      context.become(connecting)

    case CommandFailed(write: Write) =>
      bufferFailedWrite(write)
  }

  def discardSnapshots: Actor.Receive = {
    case snapshot: TickMetricSnapshot =>
      log.warn("Connection with Graphite is not established yet, discarding TickMetricSnapshot")
  }

  def bufferFailedWrite(write: Write): Unit = {
    if(failedWrites.size >= writeRetryBufferSize) {
      log.info("The retry buffer is full, discarding a failed write command")
      failedWrites = failedWrites.tail
    }

    failedWrites = failedWrites :+ write
  }

  def flushFailedWrites(connection: ActorRef): Unit = {
    log.info("Flushing {} writes from the retry buffer", failedWrites.size)
    failedWrites.foreach(w => connection ! w)
    failedWrites = Seq.empty[Write]
  }

  def connecting: Actor.Receive = discardSnapshots orElse {
    case CommandFailed(_: Connect) =>
      log.warn("Unable to connect to Graphite, retrying in {}", connectionRetryDelay)
      startReconnecting()

    case c @ Connected(remote, local) =>
      val connection = sender()
      connection ! Register(self)
      log.info("Connected to Graphite")
      flushFailedWrites(connection)
      context.become(sending(connection))

    case CommandFailed(write: Write) =>
      log.warn("Write command to Graphite failed, adding the command to the retry buffer")
      bufferFailedWrite(write)
  }

  def sending(connection: ActorRef): Actor.Receive = {
    case snapshot: TickMetricSnapshot =>
      dispatchSnapshot(connection, snapshot)

    case _: ConnectionClosed =>
      log.warn("Disconnected from Graphite, trying to reconnect in {}", connectionRetryDelay)
      startReconnecting()

    case CommandFailed(write: Write) =>
      log.warn("Write command to Graphite failed, adding the command to the retry buffer")
      bufferFailedWrite(write)
      connection ! Close
      startReconnecting()
  }

  def startReconnecting(): Unit = {
    context.become(disconnected)
    context.system.scheduler.scheduleOnce(connectionRetryDelay, self, GraphiteClient.InitiateConnection)
  }

  def dispatchSnapshot(connection: ActorRef, snapshot: TickMetricSnapshot): Unit = {
    val timestamp = snapshot.to.millis / 1000 // Turn the timestamp into seconds.

    for((entity, entitySnapshot) <- snapshot.metrics) {
      dispatchHistograms(entity, entitySnapshot.histograms)
      dispatchGauges(entity, entitySnapshot.gauges)
      dispatchMinMaxCounters(entity, entitySnapshot.minMaxCounters)
      dispatchCounters(entity, entitySnapshot.counters)
    }

    def dispatchHistograms(entity: Entity, histograms: Map[HistogramKey, Histogram.Snapshot]): Unit = histograms foreach {
      case (histogramKey, snapshot) => connection ! Write(packHistogram(metricPrefix, entity, histogramKey, snapshot, timestamp))
    }

    def dispatchGauges(entity: Entity, gauges: Map[GaugeKey, Histogram.Snapshot]): Unit = gauges foreach {
      case (gaugeKey, snapshot) => connection ! Write(packGauge(metricPrefix, entity, gaugeKey, snapshot, timestamp))
    }

    def dispatchMinMaxCounters(entity: Entity, minMaxCounters: Map[MinMaxCounterKey, Histogram.Snapshot]): Unit = minMaxCounters foreach {
      case (minMaxCounterKey, snapshot) => connection ! Write(packMinMaxCounter(metricPrefix, entity, minMaxCounterKey, snapshot, timestamp))
    }

    def dispatchCounters(entity: Entity, counters: Map[CounterKey, Counter.Snapshot]): Unit = counters foreach {
      case (counterKey, snapshot) => connection ! Write(packCounter(metricPrefix, entity, counterKey, snapshot, timestamp))
    }
  }
}

object GraphiteClient {
  case object InitiateConnection
}