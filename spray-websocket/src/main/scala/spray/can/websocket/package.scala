package spray.can

import akka.actor.ActorRef
import akka.io.Tcp
import com.typesafe.config.ConfigFactory
import java.security.MessageDigest
import scala.collection.JavaConversions._
import scala.concurrent.forkjoin.ThreadLocalRandom
import spray.can.client.ClientConnectionSettings
import spray.can.server.ServerSettings
import spray.can.websocket.compress.PMCE
import spray.can.websocket.frame.{ FrameStream, Frame }
import spray.http.HttpEntity
import spray.http.HttpHeader
import spray.http.HttpHeaders
import spray.http.HttpHeaders.Connection
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMethods
import spray.http.HttpProtocols
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes

package object websocket {

  val config = ConfigFactory.load().getConfig("spray.websocket")
  val enabledPCMEs = config.getStringList("pmce")
  val enabledUTF8Validate = config.getBoolean("enable-utf8validate")

  /**
   * Wraps a frame in a Event going up through the event pipeline
   */
  final case class FrameInEvent(frame: Frame) extends Tcp.Event

  /**
   * Wraps a frame in a Command going down through the command pipeline
   */
  final case class FrameCommand(frame: Frame) extends Tcp.Command
  final case class FrameStreamCommand(frame: FrameStream) extends Tcp.Command

  final case class FrameCommandFailed(frame: Frame, commandFailed: Tcp.CommandFailed) extends Tcp.Event

  final case class Send(frame: Frame)
  final case class SendStream(frame: FrameStream)

  case object UpgradedToWebSocket

  /**
   * pipeline stage of websocket
   *
   * TODO websocketFrameSizeLimit as setting option?
   * TODO isAutoPongEnabled as setting options?
   */
  def pipelineStage(serverHandler: ActorRef,
                    wsContext: HandshakeContext,
                    wsFrameSizeLimit: Int = Int.MaxValue,
                    maskGen: Option[() => Array[Byte]] = None) = (settings: ServerSettings) => {

    WebSocketFrontend(settings, serverHandler) >>
      FrameComposing(wsFrameSizeLimit, wsContext) >>
      FrameParsing(wsFrameSizeLimit) >>
      FrameRendering(maskGen, wsContext)
  }

  def defaultMaskGen(): Array[Byte] = {
    val mask = Array.fill[Byte](4)(0)
    ThreadLocalRandom.current.nextBytes(mask)
    mask
  }

  def clientPipelineStage(clientHandler: ActorRef,
                          wsContext: HandshakeContext,
                          wsFrameSizeLimit: Int = Int.MaxValue,
                          maskGen: Option[() => Array[Byte]] = Some(defaultMaskGen)) = (settings: ClientConnectionSettings) => {

    WebSocketFrontend(settings, clientHandler) >>
      FrameComposing(wsFrameSizeLimit, wsContext) >>
      FrameParsing(wsFrameSizeLimit) >>
      FrameRendering(maskGen, wsContext)
  }

  def basicHandshakeRepuset(uriPath: String) = HttpRequest(HttpMethods.GET, uriPath, List(
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")))

  sealed trait Handshake {

    class Collector {
      var connection: List[String] = Nil
      var upgrade: List[String] = Nil
      var version: String = _

      var accept = ""
      var key = ""
      var protocol: List[String] = Nil
      var extensions = Map[String, Map[String, String]]()
    }

    def parseHeaders(headers: List[HttpHeader]): Option[Collector] = {
      val collector = headers.foldLeft(new Collector) {
        case (acc, Connection(connection)) =>
          acc.connection :::= connection.toList.map(_.trim).map(_.toLowerCase)
          acc
        case (acc, HttpHeader("upgrade", upgrade)) =>
          acc.upgrade :::= upgrade.split(',').toList.map(_.trim).map(_.toLowerCase)
          acc
        case (acc, HttpHeader("sec-websocket-version", version)) =>
          acc.version = version // TODO negotiation
          acc
        case (acc, HttpHeader("sec-websocket-key", key)) =>
          acc.key = key
          acc
        case (acc, HttpHeader("sec-websocket-accept", accept)) =>
          acc.accept = accept
          acc
        case (acc, HttpHeader("sec-websocket-protocol", protocol)) =>
          acc.protocol :::= protocol.split(',').toList.map(_.trim)
          acc
        case (acc, HttpHeader("sec-websocket-extensions", extensions)) =>
          acc.extensions ++= parseExtensions(extensions)
          acc
        case (acc, _) =>
          acc
      }

      if (collector.upgrade.contains("websocket") && collector.connection.contains("upgrade")) {
        Some(collector)
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
          val protocols = collector.protocol
          val extentions = collector.extensions

          val pcme = enabledPCMEs.find(extentions.contains(_)).map(name => PMCE(name, extentions(name))).flatten
          //if (x.client_max_window_bits == WBITS_NOT_SET) {
          //Some(HandshakeContext(null, key, protocols, extentions, pcme))
          //} else { // does not support server_max_window_bits yet
          //  Some(HandshakeFailure(protocols, extentions))
          //}
          Some(HandshakeContext(req, key, protocols, extentions, pcme))
        }
        case _ => None
      }
    }
  }

  object HandshakeResponse extends Handshake {

    def unapply(resp: HttpResponse): Option[HandshakeState] = resp match {
      case HttpResponse(StatusCodes.SwitchingProtocols, entity, headers, HttpProtocols.`HTTP/1.1`) => tryHandshake(headers, entity)
      case _ => None
    }

    def tryHandshake(headers: List[HttpHeader], entity: HttpEntity): Option[HandshakeState] = {

      parseHeaders(headers) match {
        case Some(collector) => {
          val key = collector.accept
          val protocols = collector.protocol
          val extentions = collector.extensions

          val pcme = enabledPCMEs.find(extentions.contains(_)).map(name => PMCE(name, extentions(name))).flatten
          //if (x.client_max_window_bits == WBITS_NOT_SET) {
          //Some(HandshakeContext(null, key, protocols, extentions, pcme))
          //} else { // does not support server_max_window_bits yet
          //  Some(HandshakeFailure(protocols, extentions))
          //}
          Some(HandshakeContext(null, key, protocols, extentions, pcme))
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
      protocol: List[String],
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
      protocol: List[String],
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
      new HandshakeContext(request, acceptanceKey, protocol, extensions, pmce) {
        override def response = resp
      }
  }
}

