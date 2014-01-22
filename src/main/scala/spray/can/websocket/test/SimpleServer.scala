package spray.can.websocket.test

import akka.io.IO
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging }
import akka.pattern._
import java.util.UUID
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket.WebSocket
import spray.can.websocket.frame.BinaryFrame
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.TextFrame
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest, Uri, HttpResponse, StatusCodes, SomeOrigins }
import scala.concurrent.duration._
import HttpHeaders._
import HttpMethods._

object SimpleServer extends App with MySslConfiguration {

  class SocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        log.info("Connected to HttpListener: {}", sender.path)
        sender ! Http.Register(self)

      // accept client request for upgrading to websocket
      case req @ WebSocket.UpgradeRequest(header) =>
        log.info("Got websocket upgrade req: {}", req)
        sender ! UHttp.Upgrade(WebSocket.pipelineStage(self), Some(WebSocket.acceptResp(header)))
      // NOTE to get socket.io connected, we need to send back a connect packet.

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")

      // just send back frames for Autobahn test suite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        //log.info("got {}", x)
        sender ! x

      case x: Frame =>
      //log.info("Got frame: {}", x)

      // --- test code for socket.io etc

      case HttpRequest(GET, Uri.Path("/pingpingping"), _, _, _) =>
        sender ! HttpResponse(entity = "PONG!PONG!PONG!")

      // some testing code for socket.io
      case HttpRequest(GET, Uri.Path("/socket.io/1/"), headers, _, _) =>
        //log.info("socket.io handshake: {}", uri.toRelative)
        val origins = headers.collectFirst { case HttpHeaders.Origin(xs) => xs; case _ => Nil }.get

        val sessionId = UUID.randomUUID
        val heartbeatTimeout = 15
        val connectionClosingTimeout = 10
        val supportedTransports = List("websocket", "xhr-polling")
        val entity = List(sessionId, heartbeatTimeout, connectionClosingTimeout, supportedTransports.mkString(",")).mkString(":")

        val socketioHandshakerResp = HttpResponse(
          status = StatusCodes.OK,
          entity = entity,
          headers = List(
            HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
            HttpHeaders.`Access-Control-Allow-Credentials`(true)))

        sender ! socketioHandshakerResp

      case x: HttpRequest =>
        log.info("Got http req uri = {}", x.uri.toRelative)
        log.info(x.toString)

    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val worker = system.actorOf(Props(classOf[SocketServer]), "websocket")

  IO(UHttp) ! Http.Bind(
    worker,
    "localhost",
    8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()

}
