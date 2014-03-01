package spray.can.server.websocket

import org.scalatest.{BeforeAndAfterAll, FunSuite}
import akka.actor._
import spray.can.websocket.examples.MySslConfiguration
import spray.can.{websocket, Http}
import spray.can.server.{ServerSettings, UHttp}
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
import java.io.ByteArrayInputStream
import com.typesafe.config.{ConfigFactory, Config}
import spray.can.client.ClientConnectionSettings

/**
 *
 * Copyright (c) 2011, Wandou Labs and/or its affiliates. All rights reserved.
 * WANDOU LABS PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
class UHttpTest extends FunSuite with BeforeAndAfterAll with Eventually with MySslConfiguration {
  val testConf: Config = ConfigFactory.parseString("""
    akka {
      loglevel = WARNING
    }""")

  implicit val system = ActorSystem("UHttpTestSystem", testConf)
  implicit val timeout = akka.util.Timeout(5 seconds)
  import system.dispatcher

  override def afterAll: Unit = system.shutdown()

  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(new WebSocketWorker(serverConnection)))
        serverConnection ! Http.Register(conn)
      case PingFrame(payload) =>
        sender() ! PongFrame(payload)
    }
  }

  class WebSocketWorker(var serverConnection: ActorRef) extends websocket.WebSocketConnection {
    def businessLogic: Receive = {
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

      case UHttp.Upgraded(wsContext) =>
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

  def setupConnection(port: Int, req: HttpRequest, ssl: Boolean): ActorRef = {
    val server = system.actorOf(Props(new WebSocketServer))
    val client = system.actorOf(Props(new WebsocketClient(req)))

    IO(UHttp).tell(Http.Bind(server, "localhost", port, settings = Some(ServerSettings(system).copy(sslEncryption = ssl))), server)
    Thread.sleep(100)
    IO(UHttp).tell(Http.Connect("localhost", port, sslEncryption = ssl, settings = Some(ClientConnectionSettings(system))), client)

    eventually {
      assert(Await.result(client ? "upgraded?", 1 seconds) == true)
    }(PatienceConfig(timeout = Duration.Inf))

    client
  }

  def randomPort() = 1000 + util.Random.nextInt(10000)

  def runTest(req: HttpRequest)(test: ActorRef => Unit) {
    test(setupConnection(randomPort(), req, ssl = false))
    test(setupConnection(randomPort(), req, ssl = true))
  }

  test("handshake") {
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    runTest(req) {
      client =>
        val probe = TestProbe()
        probe.send(client, Send(TextFrame(ByteString("123"))))
        probe.expectMsg(TextFrame(ByteString("123")))
        probe.send(client, Send(CloseFrame()))
    }
  }

  test("handshake with permessage-deflate") {
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
      HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate")
    ))

    runTest(req) {
      client =>
        val probe = TestProbe()
        probe.send(client, Send(TextFrame(ByteString("123"))))
        probe.expectMsg(TextFrame(ByteString("123")))
        probe.send(client, Send(CloseFrame()))
    }
  }

  test("ping pong") {
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    runTest(req) {
      client =>
        val probe = TestProbe()
        probe.send(client, Send(PingFrame()))
        probe.expectMsg(PongFrame())
        probe.send(client, Send(CloseFrame()))
    }
  }

  test("Frame Stream") {
    val req = HttpRequest(HttpMethods.GET, "/mychat", List(
      HttpHeaders.Connection("Upgrade"),
      HttpHeaders.RawHeader("Upgrade", "websocket"),
      HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
      HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw==")
    ))

    runTest(req) {
      client =>
        val probe = TestProbe()
        val frame = TextFrameStream(1, new ByteArrayInputStream("a very very long string".getBytes("UTF-8")))
        probe.send(client, SendStream(frame))
        probe.expectMsg(TextFrame(ByteString("a very very long string")))
    }
  }
}
