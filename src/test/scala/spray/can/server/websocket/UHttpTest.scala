package spray.can.server.websocket

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.{ IO, Tcp }
import akka.pattern._
import akka.testkit.TestProbe
import akka.util.ByteString
import com.typesafe.config.{ ConfigFactory, Config }
import java.io.ByteArrayInputStream
import org.scalatest.{ BeforeAndAfterAll, FunSuite }
import org.scalatest.concurrent.Eventually
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.can.Http
import spray.can.client.ClientConnectionSettings
import spray.can.server.{ ServerSettings, UHttp }
import spray.can.websocket
import spray.can.websocket.Send
import spray.can.websocket.SendStream
import spray.can.websocket.examples.MySslConfiguration
import spray.can.websocket.frame.BinaryFrame
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.PingFrame
import spray.can.websocket.frame.PongFrame
import spray.can.websocket.frame.TextFrame
import spray.can.websocket.frame.TextFrameStream
import spray.http.HttpHeaders
import spray.http.HttpRequest

class UHttpTest extends FunSuite with BeforeAndAfterAll with Eventually with MySslConfiguration {
  val testConf: Config = ConfigFactory.load

  implicit val system = ActorSystem("UHttpTestSystem", testConf)
  implicit val timeout = akka.util.Timeout(5.seconds)
  import system.dispatcher

  override def afterAll: Unit = system.shutdown()

  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(Props(new WebSocketWorker(serverConnection)))
        serverConnection ! Http.Register(conn)
    }
  }

  class WebSocketWorker(var serverConnection: ActorRef) extends websocket.WebSocketConnection {
    override def handshaking: Receive = {

      // when a client request for upgrading to websocket comes in, we send
      // UHttp.Upgrade to upgrade to websocket pipelines with an accepting response.
      case websocket.HandshakeRequest(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure => sender() ! wsFailure.response
          case wsContext: websocket.HandshakeContext => sender() ! UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
        }

      // upgraded successfully
      case UHttp.Upgraded =>
        log.debug("{} upgraded to WebSocket.", self)
        context.become(businessLogic orElse closeLogic)
    }

    def businessLogic: Receive = {
      case x: BinaryFrame =>
        log.info("Server BinaryFrame Received:" + x)
        sender() ! x

      case x: TextFrame =>
        if (x.payload.length <= 5) {
          log.info("Server TextFrame Received:" + x)
          sender() ! x
        } else {
          log.info("Server Large TextFrame Received:" + x)
          sender() ! TextFrameStream(5, new ByteArrayInputStream(x.payload.toArray))
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
        log.info("Client Connected: " + sender())
        connection = sender()
        sender() ! req

      case resp @ websocket.HandshakeResponse(state) =>
        state match {
          case wsFailure: websocket.HandshakeFailure =>
          case wsContext: websocket.HandshakeContext => sender() ! UHttp.UpgradeClient(websocket.clientPipelineStage(self, wsContext), resp)
        }

      case UHttp.Upgraded =>
        log.info("Client Upgraded!")
        connection = sender()
        context.become(upgraded)

      case x =>
        log.info("Got {}", x)
    }

    def upgraded: Receive = {
      case Send(frame) =>
        log.info("Client Frame Send")
        commander = sender()
        connection ! frame

      case SendStream(frame) =>
        log.info("Client FrameStream Send")
        commander = sender()
        connection ! frame

      case f: Frame =>
        log.info("Client Frame Received:" + f)
        commander ! f

      case "upgraded?" => sender() ! true
    }

  }

  def setupConnection(port: Int, req: HttpRequest, ssl: Boolean): ActorRef = {
    val server = system.actorOf(Props(new WebSocketServer))
    val client = system.actorOf(Props(new WebsocketClient(req)))

    IO(UHttp).tell(Http.Bind(server, "localhost", port, settings = Some(ServerSettings(system).copy(sslEncryption = ssl))), server)
    Thread.sleep(100)
    IO(UHttp).tell(Http.Connect("localhost", port, sslEncryption = ssl, settings = Some(ClientConnectionSettings(system))), client)

    eventually {
      assert(Await.result(client ? "upgraded?", 1.seconds) == true)
    }(PatienceConfig(timeout = Duration.Inf))

    client
  }

  def randomPort() = 1000 + util.Random.nextInt(10000)

  def runTest(req: HttpRequest)(test: ActorRef => Unit) {
    test(setupConnection(randomPort(), req, ssl = false))
    test(setupConnection(randomPort(), req, ssl = true))
  }

  test("handshake") {
    val req = websocket.basicHandshakeRepuset("/mychat")

    runTest(req) { client =>
      val probe = TestProbe()
      probe.send(client, Send(TextFrame(ByteString("123"))))
      probe.expectMsg(TextFrame(ByteString("123")))
      probe.send(client, Send(CloseFrame()))
    }
  }

  test("handshake with permessage-deflate") {
    val basicReq = websocket.basicHandshakeRepuset("/mychat")
    val req = basicReq.withHeaders(HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate") :: basicReq.headers)

    runTest(req) { client =>
      val probe = TestProbe()
      probe.send(client, Send(TextFrame(ByteString("123"))))
      probe.expectMsg(TextFrame(ByteString("123")))
      probe.send(client, Send(CloseFrame()))
    }
  }

  test("ping pong") {
    val req = websocket.basicHandshakeRepuset("/mychat")

    runTest(req) { client =>
      val probe = TestProbe()
      probe.send(client, Send(PingFrame()))
      probe.expectMsg(PongFrame())
      probe.send(client, Send(CloseFrame()))
    }
  }

  test("Frame Stream") {
    val req = websocket.basicHandshakeRepuset("/mychat")

    runTest(req) { client =>
      val probe = TestProbe()
      val frame = TextFrameStream(1, new ByteArrayInputStream("a very very long string".getBytes("UTF-8")))
      probe.send(client, SendStream(frame))
      probe.expectMsg(TextFrame(ByteString("a very very long string")))
    }
  }
}
