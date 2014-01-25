package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging }
import akka.io.IO
import akka.io.Tcp
import akka.pattern._
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.BinaryFrame
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.TextFrame
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest }
import scala.concurrent.duration._
import HttpHeaders._
import HttpMethods._

object SimpleServer extends App with MySslConfiguration {

  class WebSocketServer extends Actor with ActorLogging {
    def receive = upgrading orElse businessLogic

    def upgrading: Receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        sender ! Http.Register(self)

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case websocket.UpgradeRequest(header) =>
        sender ! UHttp.Upgrade(websocket.pipelineStage(self), Some(websocket.acceptResp(header)))

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Http Upgraded!")
    }

    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender ! x

      case x: HttpRequest => // do something

    }
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val worker = system.actorOf(Props(classOf[WebSocketServer]), "websocket")

  IO(UHttp) ! Http.Bind(worker, "localhost", 8080)

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
