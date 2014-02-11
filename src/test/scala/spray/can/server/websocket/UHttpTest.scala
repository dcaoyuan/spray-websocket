package spray.can.server.websocket

import org.scalatest.{BeforeAndAfterAll, FunSuite}
import akka.actor._
import spray.can.websocket.examples.MySslConfiguration
import spray.can.{websocket, Http}
import spray.can.server.UHttp
import spray.can.websocket.frame.{Frame, TextFrame, BinaryFrame}
import spray.http.{HttpHeaders, HttpMethods}
import akka.io.{IO, Tcp}
import spray.http.HttpRequest
import spray.can.websocket.frame.Send
import scala.Some
import spray.http.HttpResponse
import scala.concurrent.Await
import org.scalatest.concurrent.Eventually
import akka.pattern._
import scala.concurrent.duration._
import akka.util.ByteString
import akka.testkit.TestProbe

/**
 *
 * Copyright (c) 2011, Wandou Labs and/or its affiliates. All rights reserved.
 * WANDOU LABS PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
class UHttpTest extends FunSuite with BeforeAndAfterAll with Eventually with MySslConfiguration {
  implicit val system = ActorSystem()
  implicit val timeout = akka.util.Timeout(5 seconds)

  override def afterAll: Unit = system.shutdown()

  class WebSocketServer extends Actor with ActorLogging {
    def receive = upgrading orElse businessLogic

    def upgrading: Receive = {
      // when a new connection comes in we register ourselves as the connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        sender ! Http.Register(self)

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case websocket.HandshakeRequest(state) =>
        state match {
          case x: websocket.HandshakeFailure => sender() ! x.response
          case x: websocket.HandshakeSuccess => sender() ! UHttp.Upgrade(websocket.pipelineStage(self, x), Some(x.response))
        }

      // upgraded successfully
      case UHttp.Upgraded =>
        log.info("Server Upgraded!")
    }

    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x@(_: BinaryFrame | _: TextFrame) =>
        log.info("Server Frame1 Received")
        sender ! x

      case x: HttpRequest => // do something
        log.info("Server HttpRequest Received")

      case x: Tcp.ConnectionClosed =>
        log.info("Server Close")

      case x => log.error("Server Unknown " + x)
    }
  }

  val req = HttpRequest(HttpMethods.GET, "/mychat", List(
    HttpHeaders.Host("localhost", 8080),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
  ))

  class WebsocketClient extends Actor with ActorLogging {
    var connection: ActorRef = null
    var commander: ActorRef = null

    def receive = {
      case x: Http.Connected =>
        log.info("Client Connected: " + sender)
        connection = sender
        connection ! UHttp.UpgradeClient(websocket.clientPipelineStage(self), Option(req))

      case UHttp.Upgraded =>
        log.info("Client Upgraded!")
        connection = sender
        context.become(upgraded)
    }

    def upgraded: Receive = {
      case Send(frame) =>
        log.info("Client Frame Send")
        commander = sender
        connection ! frame

      case f: Frame =>
        log.info("Client Frame Received:" + f)
        commander ! f

      case "upgraded?" => sender ! true
    }

  }

  test("handshake") {
    val server = system.actorOf(Props(new WebSocketServer), "server")
    val client = system.actorOf(Props(new WebsocketClient), "client")

    val probe = TestProbe()

    IO(UHttp).tell(Http.Bind(server, "localhost", 8080), server)
    IO(UHttp).tell(Http.Connect("localhost", 8080), client)

    eventually {
      assert(Await.result(client ? "upgraded?", 1 seconds) == true)
    }(PatienceConfig(timeout = Duration.Inf))

    probe.send(client, Send(TextFrame(ByteString("123"))))
    probe.expectMsg(TextFrame(ByteString("123")))
  }
}
