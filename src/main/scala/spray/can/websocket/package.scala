package spray.can

import akka.actor.ActorRef
import akka.io.Tcp
import java.security.MessageDigest
import spray.can.server.ServerSettings
import spray.can.websocket.frame.{ FrameStream, Frame }
import spray.can.websocket.server.WebSocketFrontend
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpHeaders.Connection
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMethods
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes

package object websocket {

  /**
   * Wraps a frame in a Event going up through the event pipeline
   */
  sealed trait FrameEvent extends Tcp.Event { def frame: Frame }
  final case class FrameInEvent(frame: Frame) extends FrameEvent
  final case class FrameOutEvent(frame: Frame) extends FrameEvent

  /**
   * Wraps a frame in a Command going down through the command pipeline
   */
  final case class FrameCommand(frame: Frame) extends Tcp.Command

  final case class FrameStreamCommand(frame: FrameStream) extends Tcp.Command

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
    def unapply(req: HttpRequest): Option[UpgradeState] = req match {
      case HttpRequest(HttpMethods.GET, _, UpgradeHeaders(state), _, _) => Some(state)
      case _ => None
    }
  }

  private object UpgradeHeaders {
    val acceptedVersions = Set("7", "8", "13")

    class Collector {
      var connection: String = _
      var upgrade: String = _
      var version: String = _

      var key = ""
      var protocal = "" // optional
      var extensions = "" // optional
    }

    def unapply(headers: List[HttpHeader]): Option[UpgradeState] = {
      val collector = headers.foldLeft(new Collector) { (acc, header) =>
        header match {
          case HttpHeaders.Connection(Seq(connection)) => acc.connection = connection
          case HttpHeaders.RawHeader("Upgrade", upgrate) => acc.upgrade = upgrate.toLowerCase
          case HttpHeaders.RawHeader("Sec-WebSocket-Version", version) => acc.version = version
          case HttpHeaders.RawHeader("Sec-WebSocket-Key", key) => acc.key = key
          case HttpHeaders.RawHeader("Sec-WebSocket-Protocol", protocal) => acc.protocal = protocal
          case HttpHeaders.RawHeader("Sec-WebSocket-Extensions", extensions) => acc.extensions = extensions
          case _ =>
        }

        acc
      }

      if (collector.connection == "Upgrade"
        && collector.upgrade == "websocket"
        && acceptedVersions.contains(collector.version)) {

        val key = acceptanceHash(collector.key)
        val protocols = collector.protocal.split(',').toList.map(_.trim)
        val extentions = collector.extensions.split(',').toList.map(_.trim)
        // permessage-deflate
        // permessage-bzip2
        // permessage-snappy
        val permessageDeflate = extentions.find(_ == "permessage-deflate") match {
          case Some(x) => Some(PermessageDeflate())
          case None    => None
        }
        Some(UpgradeState(key, protocols, extentions, permessageDeflate))
      } else {
        None
      }
    }
  }

  def acceptanceHash(key: String) = {
    new sun.misc.BASE64Encoder().encode(
      MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")))
  }

  def acceptanceHeaders(acceptKey: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", acceptKey))

  def acceptanceResp(state: UpgradeState) = HttpResponse(
    status = StatusCodes.SwitchingProtocols,
    headers = acceptanceHeaders(state.key))

  final case class UpgradeState(key: String, protocal: List[String], extensions: List[String],
                                permessageDeflate: Option[PermessageDeflate])

  final case class PermessageDeflate(
    server_no_context_takeover: Boolean = true,

    client_no_context_takeover: Boolean = true,

    server_max_window_bits: Int = 10, // 8 - 15

    client_max_window_bits: Int = 10 // 8 - 15
    )

}

