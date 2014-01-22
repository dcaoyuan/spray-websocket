package spray.can.websocket

import akka.actor.ActorRef
import akka.io.Tcp
import java.security.MessageDigest
import spray.can.server.ServerSettings
import spray.can.websocket.frame.Frame
import spray.can.websocket.server.WebSocketFrontend
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpHeaders.Connection
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMethods
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes
import scala.concurrent.duration.FiniteDuration

object WebSocket {

  /**
   * Wraps a frame in a Event going up through the event pipeline
   */
  sealed trait FrameEvent extends Tcp.Event { def frame: Frame }
  case class FrameInEvent(frame: Frame) extends FrameEvent
  case class FrameOutEvent(frame: Frame) extends FrameEvent

  /**
   * Wraps a frame in a Command going down through the command pipeline
   */
  case class FrameCommand(frame: Frame) extends Tcp.Command

  /**
   * pipeline stage of websocket
   *
   * TODO websocketFrameSizeLimit as setting option?
   * TODO isAutoPongEnabled as setting options?
   */
  def pipelineStage(serverHandler: ActorRef, isAutoPongEnabled: Boolean = true, websocketFrameSizeLimit: Int = Int.MaxValue, maskGen: Option[() => Array[Byte]] = None) = (settings: ServerSettings) => {
    import settings._
    import timeouts._
    WebSocketFrontend(settings, serverHandler) >>
      FrameRendering(maskGen) >>
      AutoPong(isAutoPongEnabled) ? isAutoPongEnabled >>
      FrameComposing(websocketFrameSizeLimit) >>
      FrameParsing(websocketFrameSizeLimit)
  }

  object UpgradeRequest {
    def unapply(req: HttpRequest): Option[UpgradeHeaders] = req match {
      case HttpRequest(HttpMethods.GET, _, WebSocket.UpgradeHeaders(header), _, _) => Some(header)
      case _ => None
    }
  }

  private object UpgradeHeaders {
    class Collector {
      var hasConnetion = false
      var hasUpgrade = false
      var hasVersion = false

      var key = ""
      var protocal = "" // optional
      var extensions = "" // optional
    }

    def unapply(headers: List[HttpHeader]): Option[UpgradeHeaders] = {
      val collector = headers.foldLeft(new Collector) {
        case (acc, Connection(Seq("Upgrade"))) =>
          acc.hasConnetion = true
          acc
        case (acc, RawHeader("Upgrade", value)) if value.toLowerCase == "websocket" =>
          acc.hasUpgrade = true
          acc
        case (acc, RawHeader("Sec-WebSocket-Version", "13")) =>
          acc.hasVersion = true
          acc
        case (acc, RawHeader("Sec-WebSocket-Key", key)) =>
          acc.key = key
          acc
        case (acc, RawHeader("Sec-WebSocket-Protocol", protocal)) =>
          acc.protocal = protocal
          acc
        case (acc, RawHeader("Sec-WebSocket-Extensions", extensions)) =>
          acc.extensions = extensions
          acc
        case (acc, _) =>
          acc
      }

      if (collector.hasConnetion && collector.hasUpgrade && collector.hasVersion) {
        Some(UpgradeHeaders(collector.key, collector.protocal.split(',').toList.map(_.trim), collector.extensions.split(',').toList.map(_.trim)))
      } else {
        None
      }
    }
  }
  case class UpgradeHeaders(key: String, protocal: List[String], extensions: List[String]) {
    def acceptHash = new sun.misc.BASE64Encoder().encode(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")))
  }

  def acceptHeaders(acceptKey: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", acceptKey))

  def acceptResp(x: UpgradeHeaders) = HttpResponse(
    status = StatusCodes.SwitchingProtocols,
    headers = acceptHeaders(x.acceptHash))
}

