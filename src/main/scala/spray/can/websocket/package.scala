package spray.can

import akka.actor.ActorRef
import akka.io.Tcp
import akka.util.ByteString
import java.security.MessageDigest
import scala.util.Random
import spray.can.client.ClientConnectionSettings
import spray.can.server.ServerSettings
import spray.can.websocket.compress.PMCE
import spray.can.websocket.compress.PermessageDeflate
import spray.can.websocket.frame.{ FrameStream, Frame }
import spray.http.HttpEntity
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpHeaders.Connection
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMessagePart
import spray.http.HttpProtocols
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes

package object websocket {

  /**
   * Wraps a frame in a Event going up through the event pipeline
   */
  final case class FrameInEvent(frame: Frame) extends Tcp.Event

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
  def pipelineStage(serverHandler: ActorRef, wsContext: HandshakeContext,
                    wsFrameSizeLimit: Int = Int.MaxValue,
                    maskGen: Option[() => Array[Byte]] = None) = (settings: ServerSettings) => {

    WebSocketFrontend(settings, serverHandler) >>
      FrameComposing(wsFrameSizeLimit, wsContext) >>
      FrameParsing(wsFrameSizeLimit) >>
      FrameRendering(maskGen, wsContext)
  }

  def defaultMaskGen(): Array[Byte] = {
    val mask = Array.fill[Byte](4)(0)
    Random.nextBytes(mask)
    mask
  }

  def clientPipelineStage(clientHandler: ActorRef, isAutoPongEnabled: Boolean = true, websocketFrameSizeLimit: Int = Int.MaxValue, maskGen: Option[() => Array[Byte]] = Option(defaultMaskGen)) = (settings: ClientConnectionSettings) => (state: HandshakeContext) => {
    WebSocketFrontend(settings, clientHandler) >>
      FrameComposing(websocketFrameSizeLimit, state) >>
      FrameParsing(websocketFrameSizeLimit) >>
      FrameRendering(maskGen, state)
  }

  sealed trait Handshake {

    class Collector {
      var connection: List[String] = Nil
      var upgrade: List[String] = Nil
      var version: String = _

      var accept = ""
      var key = ""
      var protocal: List[String] = Nil
      var extensions = Map[String, Map[String, String]]()
    }

    def parseHeaders(headers: List[HttpHeader]): Option[Collector] = {
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
          case RawHeader("Sec-WebSocket-Accept", accept) => acc.accept = accept
          case RawHeader("Sec-WebSocket-Protocol", protocal) => acc.protocal :::= protocal.split(',').toList.map(_.trim)
          case RawHeader("Sec-WebSocket-Extensions", extensions) => acc.extensions ++= parseExtensions(extensions)
          case _ =>
        }
        acc
      }
      Some(collector)
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

  object HandshakeRequest extends Handshake {
    val acceptedVersions = Set("13")

    def unapply(req: HttpRequest): Option[HandshakeState] = req match {
      case HttpRequest(_, uri, headers, entity, HttpProtocols.`HTTP/1.1`) => tryHandshake(req, headers, entity)
      case _ => None
    }

    def tryHandshake(req: HttpRequest, headers: List[HttpHeader], entity: HttpEntity): Option[HandshakeState] = {
      parseHeaders(headers) match {
        case Some(collector) if acceptedVersions.contains(collector.version) => {
          val key = acceptanceHash(collector.key)
          val protocols = collector.protocal
          val extentions = collector.extensions

          extentions.get("permessage-deflate").map(PermessageDeflate(_)) match {
            case Some(pcme) =>
              //if (x.client_max_window_bits == WBITS_NOT_SET) {
              Some(HandshakeContext(req, key, protocols, extentions, Some(pcme)))
            //} else { // does not support server_max_window_bits yet
            //  Some(HandshakeFailure(protocols, extentions))
            //}
            case None => Some(HandshakeContext(req, key, protocols, extentions, None))
        }
          }
        case _ => None
      }
    }
  }

  object HandshakeResponse extends Handshake {

    def unapply(resp: HttpResponse): Option[HandshakeContext] = resp match {
      case HttpResponse(StatusCodes.SwitchingProtocols, entity, headers, HttpProtocols.`HTTP/1.1`) => tryHandshake(headers, entity)
      case _ => None
    }

    def tryHandshake(headers: List[HttpHeader], entity: HttpEntity): Option[HandshakeContext] = {
      parseHeaders(headers) match {
        case Some(collector) => {
          val key = collector.accept
          val protocols = collector.protocal
          val extentions = collector.extensions

          extentions.get("permessage-deflate").map(PermessageDeflate(_)) match {
            case Some(pcme) =>
              //if (x.client_max_window_bits == WBITS_NOT_SET) {
              Some(HandshakeContext(null, key, protocols, extentions, Some(pcme)))
            //} else { // does not support server_max_window_bits yet
            //  Some(HandshakeFailure(protocols, extentions))
            //}
            case None => Some(HandshakeContext(null, key, protocols, extentions, None))
        }
          }
        case _ => None
      }
    }
  }

  private def acceptanceHash(key: String) = new sun.misc.BASE64Encoder().encode(
    MessageDigest.getInstance("SHA-1").digest(
      key.getBytes("UTF-8") ++ "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("UTF-8")))

  sealed trait HandshakeState {
    def request: HttpRequest
    def response: HttpResponse
  }

  final case class HandshakeFailure(
    request: HttpRequest,
    protocal: List[String],
    extensions: Map[String, Map[String, String]]) extends HandshakeState {

    private def responseHeaders: List[HttpHeader] = List(
      HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

    def response = HttpResponse(
      status = StatusCodes.BadRequest,
      headers = responseHeaders)

  }

  case class HandshakeContext(
    request: HttpRequest,
    acceptanceKey: String,
    protocal: List[String],
    extensions: Map[String, Map[String, String]],
    pmce: Option[PMCE]) extends HandshakeState {

    def isCompressionNegotiated = pmce.isDefined

    private def responseHeaders: List[HttpHeader] = List(
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Sec-WebSocket-Accept", acceptanceKey)) :::
      pmce.map(_.extensionHeader).fold(List[HttpHeader]())(List(_))

    def response = HttpResponse(
      status = StatusCodes.SwitchingProtocols,
      headers = responseHeaders)

    def withResponse(resp: HttpResponse) =
      new HandshakeContext(request, acceptanceKey, protocal, extensions, pmce) {
        override def response = resp
      }
  }

  case class HandshakeResponseEvent(resp: HttpMessagePart, data: ByteString) extends Tcp.Event
}

