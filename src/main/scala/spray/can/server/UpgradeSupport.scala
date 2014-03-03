package spray.can.server

import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionKey
import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString
import scala.annotation.tailrec
import spray.can.Http
import spray.can.HttpExt
import spray.can.HttpManager
import spray.can.client.{ UpgradableHttpClientSettingsGroup, ClientConnectionSettings }
import spray.can.parsing.HttpResponsePartParser
import spray.can.parsing.Parser
import spray.can.parsing.Result
import spray.http.ErrorInfo
import spray.http.HttpEntity
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpMethods
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
            context.log.debug("Server upgraded.")
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

      def defaultState: State = new State {
        val defaultPipelines = defaultStage(context, commandPL, eventPL)
        val cpl = defaultPipelines.commandPipeline

        val commandPipeline: CPL = {
          case UHttp.UpgradeClient(pipelineStage, request) =>
            cpl(Http.MessageCommand(request))
            become(upgradingState(defaultPipelines, pipelineStage))

          case cmd => cpl(cmd)
        }

        val eventPipeline = defaultPipelines.eventPipeline
      }

      def upgradingState(defaultPipelines: Pipelines, upgradePipelineStage: HttpResponse => Option[ClientConnectionSettings => RawPipelineStage[PipelineContext]]) = new State {

        case class UpgradeResponseReady(response: HttpResponse, remainingData: ByteString) extends Tcp.Event

        /**
         * Split non entity allowed http response data to http part and remaining data
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
        var remainingData = ByteString.empty
        var parser: Parser = new HttpResponsePartParser(settings.parserSettings)() {
          setRequestMethodForNextResponse(HttpMethods.GET)

          override def parseEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: Option[HttpHeaders.`Content-Length`],
                                   cth: Option[HttpHeaders.`Content-Type`], teh: Option[HttpHeaders.`Transfer-Encoding`], hostHeaderPresent: Boolean,
                                   closeAfterResponseCompletion: Boolean): Result = {
            remainingData = input.drop(bodyStart)
            emit(message(headers, HttpEntity.Empty), closeAfterResponseCompletion) { Result.IgnoreAllFurtherInput }
          }
        }

        @tailrec
        def handleParsingResult(result: Result): Unit = result match {
          case Result.Emit(part, closeAfterResponseCompletion, continue) =>
            eventPipeline(UpgradeResponseReady(part.asInstanceOf[HttpResponse], remainingData))
            remainingData = ByteString.empty
            handleParsingResult(continue())

          case Result.NeedMoreData(next)    => parser = next

          case Result.ParsingError(_, info) => handleError(info)

          case Result.IgnoreAllFurtherInput =>

          case Result.Expect100Continue(continue) =>
            handleParsingResult(continue())
        }

        val commandPipeline = defaultPipelines.commandPipeline

        val eventPipeline: EPL = {
          case Tcp.Received(data) =>
            handleParsingResult(parser(data))

          case UpgradeResponseReady(response, remainingData) =>
            // when response was parsed, we should become to upgradedState immediately
            // so as to receive and process incoming data under new protocal.
            // @Note the data may keep in coming during shift the state.
            upgradePipelineStage(response) match {
              case Some(stage) =>
                val upgradedPipelines = upgradedState(stage(settings)(context, commandPL, eventPL))
                become(upgradedPipelines)
                upgradedPipelines.eventPipeline(UHttp.Upgraded)
                context.log.debug("Client upgraded.")
                upgradedPipelines.eventPipeline(Tcp.Received(remainingData))

              case None => become(defaultState)
            }

          case ev => defaultPipelines.eventPipeline(ev)
        }

        def handleError(info: ErrorInfo): Unit = {
          context.log.warning("Received illegal response: {}", info.formatPretty)
          commandPL(Http.Close)
          parser = Result.IgnoreAllFurtherInput
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
   * @param response  response that should send to client to notify the sucess of upgrading.
   */
  final case class UpgradeServer(pipelineStage: ServerSettings => RawPipelineStage[SslTlsContext], response: HttpResponse) extends Tcp.Command
  /**
   * @param pipelineStage
   * @param response  response that have got from server to notify the success of upgrading. (May contain useful data in entity.)
   * @param reqeust   upgarde request that will be sent to server
   */
  final case class UpgradeClient(pipelineStage: HttpResponse => Option[ClientConnectionSettings => RawPipelineStage[PipelineContext]], request: HttpRequest) extends Tcp.Command

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

