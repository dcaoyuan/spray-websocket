package spray.can.server

import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionKey
import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString
import spray.can.Http
import spray.can.HttpExt
import spray.can.HttpManager
import spray.can.client.{ UpgradableHttpClientSettingsGroup, ClientConnectionSettings }
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.io.DynamicPipelines
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.RawPipelineStage
import spray.io.SslTlsContext

object UpgradeSupport {

  def apply[C <: SslTlsContext](settings: ServerSettings)(defaultStage: RawPipelineStage[C]) = new RawPipelineStage[C] {
    def apply(context: C, commandPL: CPL, eventPL: EPL): Pipelines = new DynamicPipelines {
      become(defaultState)

      def defaultState = new State {
        val defaultPipelines = defaultStage(context, commandPL, eventPL)
        val cpl = defaultPipelines.commandPipeline

        val commandPipeline: CPL = {
          case UHttp.UpgradeServer(pipelineStage, response) ⇒
            cpl(Http.MessageCommand(response))
            val upgradedPipelines = upgradedState(pipelineStage(settings)(context, commandPL, eventPL))
            become(upgradedPipelines)
            upgradedPipelines.eventPipeline(UHttp.Upgraded)

          case cmd => cpl(cmd)
        }

        val eventPipeline = defaultPipelines.eventPipeline
      }

      def upgradedState(pipelines: Pipelines) = new State {
        val commandPipeline = pipelines.commandPipeline
        val epl = pipelines.eventPipeline

        val eventPipeline: EPL = {
          case ev: AckEventWithReceiver => // rounding-up works, just drop it
          case ev                       => epl(ev)
        }
      }

    }
  }

  def apply[C <: PipelineContext](settings: ClientConnectionSettings)(defaultStage: RawPipelineStage[C]) = new RawPipelineStage[C] {
    def apply(context: C, commandPL: CPL, eventPL: EPL): Pipelines = new DynamicPipelines {
      become(defaultState)

      def defaultState = new State {
        val defaultPipelines = defaultStage(context, commandPL, eventPL)
        val cpl = defaultPipelines.commandPipeline
        val epl = defaultPipelines.eventPipeline

        private var underUpgradeRequest: Boolean = _
        private var remainingData: Option[ByteString] = None

        val commandPipeline: CPL = {
          case UHttp.UpgradeRequest(request) =>
            underUpgradeRequest = true
            cpl(Http.MessageCommand(request))

          case UHttp.UpgradeClient(pipelineStage, response) =>
            val upgradedPipelines = upgradedState(pipelineStage(settings)(context, commandPL, eventPL))
            become(upgradedPipelines)
            upgradedPipelines.eventPipeline(UHttp.Upgraded)
            remainingData foreach { data =>
              remainingData = None
              upgradedPipelines.eventPipeline(Tcp.Received(data))
            }

          case cmd => cpl(cmd)
        }

        val eventPipeline: EPL = {
          case Tcp.Received(data) if underUpgradeRequest =>
            underUpgradeRequest = false
            val (httpPart, followedData) = splitHttpPart(data)
            if (followedData.nonEmpty) {
              remainingData = Some(followedData)
            }
            epl(Tcp.Received(httpPart))

          case ev => epl(ev)
        }
      }

      def upgradedState(pipelines: Pipelines) = new State {
        val commandPipeline = pipelines.commandPipeline
        val epl = pipelines.eventPipeline

        val eventPipeline: EPL = {
          case ev: AckEventWithReceiver => // rounding-up works, just drop it
          case ev                       => epl(ev)
        }
      }

      /**
       * Split non entity allowed http response data to http part and followed data
       *
       * According to Http spec @see http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.3
       *
       *   All 1xx (informational), 204 (no content), and 304 (not modified) responses
       *   MUST NOT include a message-body.
       *
       * But under 101 Switching Protocols, there could be continuated data followed
       * by http response, which could be treated as data after protocol switched and
       * should be processed by switched protocol instead of http protocol.
       *
       * Anyway, we split the http part as normal http response, and the remaining data
       * for new protocol processing later.
       */
      private def splitHttpPart(input: ByteString): (ByteString, ByteString) = {
        var i = 0
        val len = input.length
        while (i < len) {
          if (i + 3 < len
            && input(i) == '\r'
            && input(i + 1) == '\n'
            && input(i + 2) == '\r'
            && input(i + 3) == '\n') {
            return input.splitAt(i + 4)
          } else if (i + 1 < len
            && input(i) == '\n'
            && input(i + 1) == '\n') {
            return input.splitAt(i + 2)
          }
          i += 1
        }
        (input, ByteString.empty)
      }

    }
  }
}

/**
 * UpgradableHttp extension
 *
 * UHttp, UHttpExt and UHttpManager is for getting HttpListener
 * to be replaced by UpgradableHttpListener only.
 *
 * Usage:
 *    IO(UHttp) ! Http.Bind
 * instead of:
 *    IO(Http) ! Http.Bind
 *
 * clould be in spray.can.Http, since Upgrade is part of HTTP spec.
 */
object UHttp extends ExtensionKey[UHttpExt] {
  /**
   * @param pipelineStage
   * @paran response  response that should send to client to notify the sucess of upgrading.
   */
  final case class UpgradeServer(pipelineStage: ServerSettings => RawPipelineStage[SslTlsContext], response: HttpResponse) extends Tcp.Command
  /**
   * @param pipelineStage
   * @paran response  response that have got from server to notify the success of upgrading. (May contain useful data in entity.)
   */
  final case class UpgradeClient(pipelineStage: ClientConnectionSettings => RawPipelineStage[PipelineContext], response: HttpResponse) extends Tcp.Command

  final case class UpgradeRequest(request: HttpRequest) extends Tcp.Command

  case object Upgraded extends Tcp.Event
}

class UHttpExt(system: ExtendedActorSystem) extends HttpExt(system) {
  override val manager = system.actorOf(
    props = Props(new UHttpManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-UHTTP") // should be unique name
}

private class UHttpManager(httpSettings: HttpExt#Settings) extends HttpManager(httpSettings) {

  override def newHttpListener(commander: ActorRef, bind: Http.Bind, httpSettings: HttpExt#Settings) = {
    new UpgradableHttpListener(commander, bind, httpSettings)
  }

  override def newHttpClientSettingsGroup(settings: ClientConnectionSettings, httpSettings: HttpExt#Settings) = {
    new UpgradableHttpClientSettingsGroup(settings, httpSettings)
  }
}

