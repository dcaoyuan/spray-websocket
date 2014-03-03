package spray.can.websocket.examples

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.Send
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.PongFrame
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest }

/**
 * A simple client for Autobahn test.
 */
object SimpleClient extends App with MySslConfiguration {

  class WebSocketClient(val upgradeRequest: HttpRequest, onMessage: Frame => Unit, onClose: () => Unit) extends websocket.WebSocketClientConnection {

    def businessLogic: Receive = {
      case Send(frame) =>
        connection ! frame

      case frame: Frame =>
        onMessage(frame)

      case _: Http.ConnectionClosed =>
        onClose()
        context.become(closed)
    }

    def closed: Receive = {
      case e => log.debug("Receive {} after closed.", e)
    }
  }

  implicit val system = ActorSystem()

  import system.dispatcher

  val ssl = false
  val agent = "spray-websocket-client" + (if (ssl) "-ssl" else "-basic")
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

  val getCaseCountReq = HttpRequest(HttpMethods.GET, "/getCaseCount", headers)
  val getCaseCountClient = system.actorOf(Props(new WebSocketClient(getCaseCountReq,
    onMessage = { frame =>
      caseCount = frame.payload.utf8String.toInt
      println("case count: " + caseCount)
    },

    onClose = { () =>
      runNextCase(1, caseCount)
    })))

  IO(UHttp).tell(Http.Connect(host, port, ssl), getCaseCountClient)

  def runNextCase(i: Int, caseCount: Int) {
    println("run case: " + i)
    val req = HttpRequest(HttpMethods.GET, "/runCase?case=" + i + "&agent=" + agent, headers)
    var client = Actor.noSender
    client = system.actorOf(Props(new WebSocketClient(req,
      onMessage = { frame =>
        frame match {
          case _: PongFrame =>
          case _ =>
            println("got: " + frame.opcode)
            client ! Send(frame)
        }
      },

      onClose = { () =>
        if (i == caseCount) {
          updateReport()
        } else {
          runNextCase(i + 1, caseCount)
        }
      })), "client" + i)

    IO(UHttp).tell(Http.Connect(host, port, ssl), client)
  }

  def updateReport() {
    val req = HttpRequest(HttpMethods.GET, "/updateReports?agent=" + agent, headers)
    val client: ActorRef = system.actorOf(Props(new WebSocketClient(req,
      onMessage = { frame => },

      onClose = { () =>
        println("Test suite finished!")
      })))

    IO(UHttp).tell(Http.Connect(host, port, ssl), client)
  }

  readLine("Hit ENTER to exit ...\n")
  system.shutdown()
  system.awaitTermination()
}
