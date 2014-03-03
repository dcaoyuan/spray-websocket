package spray.can.server

import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionKey
import akka.actor.Props
import akka.io.Tcp
import spray.can.Http
import spray.can.HttpExt
import spray.can.HttpManager
import spray.can.client.{ UpgradableHttpClientSettingsGroup, ClientConnectionSettings }
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

        val commandPipeline: CPL = {
          case UHttp.UpgradeClient(pipelineStage, response) =>
            val upgradedPipelines = upgradedState(pipelineStage(settings)(context, commandPL, eventPL))
            become(upgradedPipelines)
            upgradedPipelines.eventPipeline(UHttp.Upgraded)
            // There should be no response.entity according to Http spec:
            // http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.3
            // We just keep the following code for reference, where, the response.entity
            // actually was dropped by spray-can with an "Illegal response..." failing.
            upgradedPipelines.eventPipeline(Tcp.Received(response.entity.data.toByteString))

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

