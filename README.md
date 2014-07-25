spray-websocket
===============

WebSocket for spray-can

<a href="https://travis-ci.org/wandoulabs/spray-websocket"><img src="https://travis-ci.org/wandoulabs/spray-websocket.png" alt="spray-websocket build status"></a> 

## Features
* Support java.util.zip based per-frame-deflate
* Pass all Autobahn test cases (both ws and wss)
   * [Autobahn test reports for server (ws)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/ws/servers/index.html)
   * [Autobahn test reports for server (wss)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/wss/servers/index.html)
   * [Autobahn test reports for client (ws)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/ws/clients/index.html)
   * [Autobahn test reports for client (wss)](http://wandoulabs.github.io/spray-websocket/autobahn-reports/wss/clients/index.html)

## Usage
The artifact is published to Sonatype, so in order to use it you just have to add the following dependency:

### Stable
```scala
libraryDependencies += "com.wandoulabs.akka" %% "spray-websocket" % "0.1.2-RC1"
```

### Snapshot
```scala
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "com.wandoulabs.akka" %% "spray-websocket" % "0.1.3-SNAPSHOT"
```

## Example
Define your WebSocketWorker by extending WebSocketConnection and overriding method 'businessLogic'.
Or, write your own WebSocketConnection.

```scala

package spray.can.websocket.examples

import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef, ActorRefFactory }
import akka.io.IO
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.{ BinaryFrame, TextFrame }
import spray.http.HttpRequest
import spray.can.websocket.FrameCommandFailed
import spray.routing.HttpServiceActor

object SimpleServer extends App with MySslConfiguration {

  final case class Push(msg: String)

  object WebSocketServer {
    def props() = Props(classOf[WebSocketServer])
  }
  class WebSocketServer extends Actor with ActorLogging {
    def receive = {
      // when a new connection comes in we register a WebSocketConnection actor as the per connection handler
      case Http.Connected(remoteAddress, localAddress) =>
        val serverConnection = sender()
        val conn = context.actorOf(WebSocketWorker.props(serverConnection))
        serverConnection ! Http.Register(conn)
    }
  }

  object WebSocketWorker {
    def props(serverConnection: ActorRef) = Props(classOf[WebSocketWorker], serverConnection)
  }
  class WebSocketWorker(val serverConnection: ActorRef) extends HttpServiceActor with websocket.WebSocketServerWorker {
    override def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

    def businessLogic: Receive = {
      // just bounce frames back for Autobahn testsuite
      case x @ (_: BinaryFrame | _: TextFrame) =>
        sender() ! x

      case Push(msg) => send(TextFrame(msg))

      case x: FrameCommandFailed =>
        log.error("frame command failed", x)

      case x: HttpRequest => // do something
    }

    def businessLogicNoUpgrade: Receive = {
      implicit val refFactory: ActorRefFactory = context
      runRoute {
        getFromResourceDirectory("webapp")
      }
    }
  }

  def doMain() {
    implicit val system = ActorSystem()
    import system.dispatcher

    val server = system.actorOf(WebSocketServer.props(), "websocket")

    IO(UHttp) ! Http.Bind(server, "localhost", 8080)

    readLine("Hit ENTER to exit ...\n")
    system.shutdown()
    system.awaitTermination()
  }

  // because otherwise we get an ambiguous implicit if doMain is inlined
  doMain()
}

```

### Run the provided example

* Build and run the project: `sbt 'project spray-websocket-examples-simple' run`
* Open your browser: [http://localhost:8080/websocket.html](http://localhost:8080/websocket.html)

## Troubleshooting
### Limited JCE Policy

If you see this error:
```
java.lang.IllegalArgumentException: Cannot support TLS_RSA_WITH_AES_256_CBC_SHA with currently installed providers
    at sun.security.ssl.CipherSuiteList.<init>(CipherSuiteList.java:92)
...
```

Download the JCE, unzip and move the two jars into `<java_install_dir>lib/security`

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html)
* [Java 7](http://www.oracle.com/technetwork/java/javase/downloads/jce-7-download-432124.html)
