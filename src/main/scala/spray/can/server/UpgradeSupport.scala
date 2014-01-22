package spray.can.server

import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionKey
import akka.actor.Props
import akka.io.Tcp
import spray.can.Http
import spray.can.HttpExt
import spray.can.HttpManager
import spray.http.HttpResponse
import spray.io.DynamicPipelines
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
          case UHttp.Upgrade(pipelineStage, resp) ⇒
            resp foreach (x ⇒ cpl(Http.MessageCommand(x)))
            val upgradedPipelines = upgradedState(pipelineStage(settings)(context, commandPL, eventPL))
            become(upgradedPipelines)

            upgradedPipelines.eventPipeline(UHttp.Upgraded)

          case cmd ⇒ cpl(cmd)
        }

        val eventPipeline = defaultPipelines.eventPipeline
      }

      def upgradedState(pipelines: Pipelines) = new State {
        val commandPipeline = pipelines.commandPipeline
        val epl = pipelines.eventPipeline

        val eventPipeline: EPL = {
          case ev: AckEventWithReceiver ⇒ // rounding-up works, just drop it
          case ev                       ⇒ epl(ev)
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
 */
object UHttp extends ExtensionKey[UHttpExt] {
  // upgrades -- clould be in spray.can.Http, since Upgrade is part of HTTP spec.
  case class Upgrade(pipelineStage: ServerSettings ⇒ RawPipelineStage[SslTlsContext], resp: Option[HttpResponse]) extends Tcp.Command
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
  //override def newHttpClientSettingsGroup(settings: ClientConnectionSettings, httpSettings: HttpExt#Settings) = {
  //  new UpgradableHttpClientSettingsGroup(settings, httpSettings)
  //}
}

