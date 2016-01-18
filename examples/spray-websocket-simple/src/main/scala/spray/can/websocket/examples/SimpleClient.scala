package spray.can.websocket.examples

import akka.actor.ActorSystem
import akka.actor.Props
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.PongFrame
import spray.http.{ HttpHeaders, HttpMethods, HttpRequest }

import scala.concurrent.Await
import scala.io.StdIn
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * A simple client for Autobahn test.
 */
object SimpleClient extends App with MySslConfiguration {

  abstract class WebSocketClient(connect: Http.Connect, val upgradeRequest: HttpRequest) extends websocket.WebSocketClientWorker {
    IO(UHttp) ! connect

    def businessLogic: Receive = {
      case frame: Frame =>
        onMessage(frame)

      case _: Http.ConnectionClosed =>
        onClose()
        context.stop(self)
    }

    def onMessage(frame: Frame)
    def onClose()
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

  val connect = Http.Connect(host, port, ssl)

  val getCaseCountReq = HttpRequest(HttpMethods.GET, "/getCaseCount", headers)
  val getCaseCountClient = system.actorOf(Props(
    new WebSocketClient(connect, getCaseCountReq) {
      var caseCount = 0

      def onMessage(frame: Frame) {
        caseCount = frame.payload.utf8String.toInt
        println("case count: " + caseCount)
      }

      def onClose() {
        runNextCase(1, caseCount)
      }
    }))

  def runNextCase(i: Int, caseCount: Int) {
    println("run case: " + i)
    val req = HttpRequest(HttpMethods.GET, "/runCase?case=" + i + "&agent=" + agent, headers)
    system.actorOf(Props(
      new WebSocketClient(connect, req) {
        def onMessage(frame: Frame) {
          frame match {
            case _: PongFrame =>
            case _            => connection ! frame
          }
        }

        def onClose() {
          if (i == caseCount) {
            updateReport()
          } else {
            runNextCase(i + 1, caseCount)
          }
        }
      }), "client" + i)
  }

  def updateReport() {
    val req = HttpRequest(HttpMethods.GET, "/updateReports?agent=" + agent, headers)
    system.actorOf(Props(
      new WebSocketClient(connect, req) {
        def onMessage(frame: Frame) {}

        def onClose() {
          println("Test suite finished!")
        }
      }))
  }

  StdIn.readLine("Hit ENTER to exit ...\n")
  system.terminate()
  Await.result(system.whenTerminated, Duration.Inf)
}
