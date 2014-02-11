package spray.can.client

import spray.can.{Http, HttpExt}
import akka.actor.Props
import spray.can.server.UpgradeSupport
import spray.can.parsing.SSLSessionInfoSupport
import spray.io._

/**
 *
 * Copyright (c) 2011, Wandou Labs and/or its affiliates. All rights reserved.
 * WANDOU LABS PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
class UpgradableHttpClientSettingsGroup(settings: ClientConnectionSettings,
                                        httpSettings: HttpExt#Settings)
  extends HttpClientSettingsGroup(settings, httpSettings) {
  override val pipelineStage = UpgradableHttpClientConnection.pipelineStage(settings)

  override def receive = {
    case connect: Http.Connect ⇒
      val commander = sender
      context.actorOf(
        props = Props(new HttpClientConnection(commander, connect, pipelineStage, settings))
          .withDispatcher(httpSettings.ConnectionDispatcher),
        name = connectionCounter.next().toString)

    case Http.CloseAll(cmd) ⇒
      val children = context.children.toSet
      if (children.isEmpty) {
        sender ! Http.ClosedAll
        context.stop(self)
      } else {
        children foreach {
          _ ! cmd
        }
        context.become(closing(children, Set(sender)))
      }

  }
}

private object UpgradableHttpClientConnection {
  def pipelineStage(settings: ClientConnectionSettings) = {
    import settings._

    UpgradeSupport(settings) {
      ClientFrontend(requestTimeout) >>
        ResponseChunkAggregation(responseChunkAggregationLimit) ? (responseChunkAggregationLimit > 0) >>
        SSLSessionInfoSupport ? parserSettings.sslSessionInfoHeader >>
        ResponseParsing(parserSettings) >>
        RequestRendering(settings) >>
        ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite)
    } >>
      SslTlsSupport(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }
}
