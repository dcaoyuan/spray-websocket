package spray.can.client

import akka.io._
import spray.can.Http.Command
import spray.can.{ Http, HttpExt }
import akka.actor._
import spray.can.server.UpgradeSupport
import spray.can.parsing.SSLSessionInfoSupport
import spray.io._

class UpgradableHttpClientSettingsGroup(
  settings: ClientConnectionSettings,
  httpSettings: HttpExt#Settings
)
    extends HttpClientSettingsGroup(settings, httpSettings) {
  override val pipelineStage = UpgradableHttpClientConnection.pipelineStage(settings)

  override def receive = {
    case connect: Http.Connect ⇒
      val commander = sender
      context.actorOf(
        props = Props(new HttpClientConnection(commander, connect, pipelineStage, settings) {
          // WORKAROUND: https://github.com/spray/spray/issues/1060
          //# final-stages
          override def baseCommandPipeline(tcpConnection: ActorRef): Pipeline[Command] = {
            case x @ (_: Tcp.WriteCommand | _: Tcp.CloseCommand) ⇒ tcpConnection ! x
            case Pipeline.Tell(receiver, msg, sender) ⇒ receiver.tell(msg, sender)
            case x @ (Tcp.SuspendReading | Tcp.ResumeReading | Tcp.ResumeWriting) ⇒ tcpConnection ! x
            case _: Droppable ⇒ // don't warn
            case cmd ⇒ log.warning("command pipeline: dropped " + cmd.getClass)
          }

          override def baseEventPipeline(tcpConnection: ActorRef): Pipeline[Event] = {
            case x: Tcp.ConnectionClosed ⇒
              log.debug("Connection was {}, awaiting TcpConnection termination...", x)
              context.become {
                case Terminated(`tcpConnection`) ⇒
                  log.debug("TcpConnection terminated, stopping")
                  context.stop(self)
              }

            case _: Droppable ⇒ // don't warn
            case ev ⇒ log.warning("event pipeline: dropped " + ev.getClass)
          }
          //#

        })
          .withDispatcher(httpSettings.ConnectionDispatcher),
        name = connectionCounter.next().toString
      )

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
        RequestRendering(settings)
    } >>
      ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
      SslTlsSupportPatched(maxEncryptionChunkSize, parserSettings.sslSessionInfoHeader) >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }
}
