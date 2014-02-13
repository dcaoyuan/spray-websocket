package spray.can.websocket.examples

import akka.actor._
import spray.can.{websocket, Http}
import spray.can.server.UHttp
import spray.can.websocket.frame._
import spray.http.{HttpHeaders, HttpMethods, HttpRequest}
import akka.io.{Tcp, IO}
import spray.can.websocket.frame.Send

object SimpleClient extends App with MySslConfiguration {

  class WebsocketClient(req: HttpRequest, onMessage: Frame => Unit, onClose: () => Unit) extends Actor with ActorLogging {
    var connection: ActorRef = null
    var commander: ActorRef = null

    def receive = {
      case x: Http.Connected =>
        connection = sender
        connection ! UHttp.UpgradeClient(websocket.clientPipelineStage(self), Option(req))

      case UHttp.Upgraded =>
        connection = sender
        context.become(upgraded)
    }

    def upgraded: Receive = {
      case Send(frame) =>
        commander = sender
        connection ! frame

      case f: Frame =>
        onMessage(f)
      case Tcp.Closed =>
        onClose()
    }
  }

  implicit val system = ActorSystem()

  import system.dispatcher

  val agent = "spray-websocket-client"
  val host = "127.0.0.1"
  val port = 9001
  val headers = List(
    HttpHeaders.Host(host, port),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
    HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

  var caseCount = 0

  val getCaseCount = HttpRequest(HttpMethods.GET, "/getCaseCount", headers)
  IO(UHttp).tell(Http.Connect(host, port), system.actorOf(Props(new WebsocketClient(getCaseCount, onMessage = frame => {
    caseCount = frame.payload.utf8String.toInt
    println("case count: " + caseCount)
  }, onClose = () => {
    runNextCase(1)
  }))))

  def runNextCase(i: Int) {
    println("run case: " + i)
    val req = HttpRequest(HttpMethods.GET, "/runCase?case=" + i + "&agent=" + agent, headers)
    var client = Actor.noSender
    client = system.actorOf(Props(new WebsocketClient(req, onMessage = frame => {
      frame match {
        case _: PongFrame =>
        case _ => client ! Send(frame)
      }
    }, onClose = () => {
      if (i == caseCount) {
        updateReport()
      } else {
        runNextCase(i + 1)
      }
    })), "client" + i)
    IO(UHttp).tell(Http.Connect(host, port), client)
  }

  def updateReport() {
    val req = HttpRequest(HttpMethods.GET, "/updateReports?agent=" + agent, headers)
    val client: ActorRef = system.actorOf(Props(new WebsocketClient(req, onMessage = frame => {
    }, onClose = () => {
      println("Test suite finished!")
    })))
    IO(UHttp).tell(Http.Connect(host, port), client)
  }

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
