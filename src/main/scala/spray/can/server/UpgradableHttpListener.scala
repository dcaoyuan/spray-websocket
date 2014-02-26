package spray.can.server

import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import spray.can.Http
import spray.can.HttpExt
import spray.can.parsing.SSLSessionInfoSupport
import spray.can.server.StatsSupport.StatsHolder
import spray.io._
import scala.Some

/**
 * The only diff from HttpListener is:
 *   private val pipelineStage = UpgradableHttpListener.pipelineStage(settings, statsHolder)
 */
class UpgradableHttpListener(bindCommander: ActorRef,
                             bind: Http.Bind,
                             httpSettings: HttpExt#Settings) extends HttpListener(bindCommander, bind, httpSettings) {
  import context.system
  import bind._

  private val connectionCounter = Iterator from 0
  private val settings = bind.settings getOrElse ServerSettings(system)
  private val statsHolder = if (settings.statsSupport) Some(new StatsHolder) else None
  private val pipelineStage = UpgradableHttpListener.pipelineStage(settings, statsHolder)

  override def connected(tcpListener: ActorRef): Receive = {
    case Tcp.Connected(remoteAddress, localAddress) ⇒
      val conn = sender()
      context.actorOf(
        props = Props(new HttpServerConnection(conn, listener, pipelineStage, remoteAddress, localAddress, settings))
          .withDispatcher(httpSettings.ConnectionDispatcher),
        name = connectionCounter.next().toString)

    case Http.GetStats            ⇒ statsHolder foreach { holder ⇒ sender() ! holder.toStats }
    case Http.ClearStats          ⇒ statsHolder foreach { _.clear() }

    case Http.Unbind(timeout)     ⇒ unbind(tcpListener, Set(sender()), timeout)

    case _: Http.ConnectionClosed ⇒
    // ignore, we receive this event when the user didn't register the handler within the registration timeout period
  }

}

/**
 * Could be the replacement for HttpServerConnection.pipelineStage
 */
private object UpgradableHttpListener {
  def pipelineStage(settings: ServerSettings, statsHolder: Option[StatsHolder]) = {
    import settings._
    import timeouts._
    UpgradeSupport(settings) {
      ServerFrontend(settings) >>
        RequestChunkAggregation(requestChunkAggregationLimit) ? (requestChunkAggregationLimit > 0) >>
        PipeliningLimiter(pipeliningLimit) ? (pipeliningLimit > 0) >>
        StatsSupport(statsHolder.get) ? statsSupport >>
        RemoteAddressHeaderSupport ? remoteAddressHeader >>
        SSLSessionInfoSupport ? parserSettings.sslSessionInfoHeader >>
        RequestParsing(settings) >>
        ResponseRendering(settings)
    } >>
      ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
      PreventHalfClosedConnections(sslEncryption) >>
      SslTlsSupportV2(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) ? sslEncryption >>
      TickGenerator(reapingCycle) ? (reapingCycle.isFinite && (idleTimeout.isFinite || requestTimeout.isFinite)) >>
      BackPressureHandling(backpressureSettings.get.noAckRate, backpressureSettings.get.readingLowWatermark) ? autoBackPressureEnabled
  }
}