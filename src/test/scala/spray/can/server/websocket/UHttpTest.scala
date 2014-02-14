package spray.can.server.websocket

import org.scalatest.{BeforeAndAfterAll, FunSuite}
import akka.actor._
import spray.can.websocket.examples.MySslConfiguration
import spray.can.{websocket, Http}
import spray.can.server.UHttp
import spray.can.websocket.frame._
import spray.http._
import akka.io.{IO, Tcp}
import scala.concurrent.Await
import org.scalatest.concurrent.Eventually
import akka.pattern._
import scala.concurrent.duration._
import akka.util.ByteString
import akka.testkit.TestProbe
import spray.http.HttpRequest
import spray.can.websocket.frame.Send
import scala.Some
import java.io.ByteArrayInputStream

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
      case x: BinaryFrame =>
        log.info("Server BinaryFrame Received:" + x)
        sender ! x

      case x: TextFrame =>
        if (x.payload.length <= 5) {
          log.info("Server TextFrame Received:" + x)
          sender ! x
        } else {
          log.info("Server Large TextFrame Received:" + x)
          sender ! TextFrameStream(5, new ByteArrayInputStream(x.payload.toArray))
        }

      case x: HttpRequest => // do something
        log.info("Server HttpRequest Received")

      case x: Tcp.ConnectionClosed =>
        log.info("Server Close")

      case x => log.error("Server Unknown " + x)
    }
  }

  class WebsocketClient(req: HttpRequest) extends Actor with ActorLogging {
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

      case SendStream(frame) =>
        log.info("Client FrameStream Send")
        commander = sender
        connection ! frame

      case f: Frame =>
        log.info("Client Frame Received:" + f)
        commander ! f

      case "upgraded?" => sender ! true
    }

  }

  def setupConnection(port: Int, req: HttpRequest): ActorRef = {
    val server = system.actorOf(Props(new WebSocketServer))
    val client = system.actorOf(Props(new WebsocketClient(req)))

    IO(UHttp).tell(Http.Bind(server, "localhost", 8080), server)
    Thread.sleep(100)
    IO(UHttp).tell(Http.Connect("localhost", 8080), client)

    eventually {
      assert(Await.result(client ? "upgraded?", 1 seconds) == true)
    }(PatienceConfig(timeout = Duration.Inf))
    client
  }

  test("handshake") {
    val port = 8080
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Host("localhost", port),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))
    val client = setupConnection(port, req)

    val probe = TestProbe()
    probe.send(client, Send(TextFrame(ByteString("123"))))
    probe.expectMsg(TextFrame(ByteString("123")))
    probe.send(client, Send(CloseFrame()))

  }

  test("handshake with permessage-deflate") {
    val port = 8081
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Host("localhost", port),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
      HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate")
    ))
    val client = setupConnection(port, req)

    val probe = TestProbe()
    probe.send(client, Send(TextFrame(ByteString("123"))))
    probe.expectMsg(TextFrame(ByteString("123")))
    probe.send(client, Send(CloseFrame()))
  }

  test("ping pong") {
    val port = 8082
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Host("localhost", port),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))
    val client = setupConnection(port, req)

    val probe = TestProbe()
    
    probe.send(client, Send(PingFrame()))
    probe.expectMsg(PongFrame())
    probe.send(client, Send(CloseFrame()))
  }

  test("Frame Stream") {
    val port = 8083
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Host("localhost", port),
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))
    val client = setupConnection(port, req)

    val probe = TestProbe()
    val frame = TextFrameStream(1, new ByteArrayInputStream("a very very long string".getBytes("UTF-8")))

    probe.send(client, SendStream(frame))
    probe.expectMsg(TextFrame(ByteString("a very very long string")))

  }
}
