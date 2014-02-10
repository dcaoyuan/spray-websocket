package spray.can

import akka.actor.ActorRef
import akka.io.Tcp
import java.security.MessageDigest
import spray.can.server.ServerSettings
import spray.can.websocket.compress.PMCE
import spray.can.websocket.compress.PermessageDeflate
import spray.can.websocket.frame.{ FrameStream, Frame }
import spray.can.websocket.server.WebSocketFrontend
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpHeaders.Connection
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMethods
import spray.http.HttpProtocols
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes
import spray.http.Uri

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
  def pipelineStage(serverHandler: ActorRef, state: HandshakeSuccess,
                    isAutoPongEnabled: Boolean = true, websocketFrameSizeLimit: Int = Int.MaxValue,
                    maskGen: Option[() => Array[Byte]] = None) = (settings: ServerSettings) => {

    WebSocketFrontend(settings, serverHandler) >>
      FrameRendering(maskGen, state) >>
      AutoPong(isAutoPongEnabled) ? isAutoPongEnabled >>
      FrameComposing(websocketFrameSizeLimit, state) >>
      FrameParsing(websocketFrameSizeLimit)
  }

  object HandshakeRequest {
    val acceptedVersions = Set("13")

    class Collector {
      var connection: List[String] = Nil
      var upgrade: List[String] = Nil
      var version: String = _

      var key = ""
      var protocal: List[String] = Nil
      var extensions = Map[String, Map[String, String]]()
    }

    def unapply(req: HttpRequest): Option[HandshakeState] = req match {
      case HttpRequest(HttpMethods.GET, uri, headers, _, HttpProtocols.`HTTP/1.1`) => tryHandshake(uri, headers)
      case _ => None
    }

    def tryHandshake(uri: Uri, headers: List[HttpHeader]): Option[HandshakeState] = {
      val collector = headers.foldLeft(new Collector) { (acc, header) =>
        header match {
          case Connection(connection) =>
            acc.connection :::= connection.toList.map(_.trim).map(_.toLowerCase)
            if (!acc.connection.contains("upgrade")) {
              return None
            }
          case RawHeader("Upgrade", upgrate) =>
            acc.upgrade :::= upgrate.split(',').toList.map(_.trim).map(_.toLowerCase)
            if (!acc.upgrade.contains("websocket")) {
              return None
            }
          case RawHeader("Sec-WebSocket-Version", version) => acc.version = version // TODO negotiation
          case RawHeader("Sec-WebSocket-Key", key) => acc.key = key
          case RawHeader("Sec-WebSocket-Protocol", protocal) => acc.protocal :::= protocal.split(',').toList.map(_.trim)
          case RawHeader("Sec-WebSocket-Extensions", extensions) => acc.extensions ++= parseExtensions(extensions)
          case _ =>
        }

        acc
      }

      if (acceptedVersions.contains(collector.version)) {
        import PermessageDeflate._
        val key = acceptanceHash(collector.key)
        val protocols = collector.protocal
        val extentions = collector.extensions

        val pcme = extentions.get("permessage-deflate") map (PermessageDeflate(_))

        pcme match {
          case Some(x) =>
            //if (x.client_max_window_bits == WBITS_NOT_SET) {
            Some(HandshakeSuccess(uri, key, protocols, extentions, pcme))
          //} else { // does not support server_max_window_bits yet
          //  Some(HandshakeFailure(protocols, extentions))
          //}
          case None => Some(HandshakeSuccess(uri, key, protocols, extentions, pcme))
        }

      } else {
        None
      }
    }

    def parseExtensions(extensions: String, removeQuotes: Boolean = true) = {
      extensions.split(',').map(_.trim).filter(_ != "").foldLeft(Map[String, Map[String, String]]()) { (acc, ext) =>
        ext.split(';') match {
          case Array(extension, ps @ _*) =>
            val params = ps.filter(_ != "").foldLeft(Map[String, String]()) { (xs, x) =>
              x.split("=").map(_.trim) match {
                case Array(key, value) => xs + (key.toLowerCase -> stripQuotes_?(value, removeQuotes))
                case Array(key)        => xs + (key.toLowerCase -> "true")
                case _                 => xs
              }
            }
            acc + (extension -> params)
          case _ =>
            acc
        }
      }
    }

    // none strict
    def stripQuotes_?(s: String, removeQuotes: Boolean) = {
      if (removeQuotes) {
        val len = s.length
        if (len >= 1 && s.charAt(0) == '"') {
          if (len >= 2 && s.charAt(len - 1) == '"') {
            s.substring(1, len - 1)
          } else {
            s.substring(1, len)
          }
        } else {
          s
        }
      } else {
        s
      }
    }
  }

  private def acceptanceHash(key: String) = new sun.misc.BASE64Encoder().encode(
    MessageDigest.getInstance("SHA-1").digest(
      key.getBytes("UTF-8") ++ "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("UTF-8")))

  sealed trait HandshakeState {
    def uri: Uri
    def response: HttpResponse
  }

  final case class HandshakeFailure(
    uri: Uri,
    protocal: List[String],
    extensions: Map[String, Map[String, String]]) extends HandshakeState {

    private def responseHeaders: List[HttpHeader] = List(
      HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

    def response = HttpResponse(
      status = StatusCodes.BadRequest,
      headers = responseHeaders)

  }

  final case class HandshakeSuccess(
    uri: Uri,
    acceptanceKey: String,
    protocal: List[String],
    extensions: Map[String, Map[String, String]],
    pmce: Option[PMCE]) extends HandshakeState {

    def isCompressionNegotiated = pmce.isDefined

    private def responseHeaders: List[HttpHeader] = List(
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Sec-WebSocket-Accept", acceptanceKey)) ::: {
        pmce map { _.extensionHeader } toList
      }

    def response = HttpResponse(
      status = StatusCodes.SwitchingProtocols,
      headers = responseHeaders)

  }

}

