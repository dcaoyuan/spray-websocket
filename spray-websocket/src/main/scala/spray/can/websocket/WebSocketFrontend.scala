package spray.can.websocket

/**
 * This pipeline stage simply forwards the events to and receives commands from
 * the given MessageHandler. It is the final stage of the websocket pipeline,
 * and is how the pipeline interacts with user code.
 *
 * @param handler the actor which will receive the incoming Frames
 */
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import spray.can.server.ServerSettings
import spray.can.server.UHttp
import spray.can.websocket.frame.FrameParser
import spray.can.websocket.frame.{ FrameStream, Frame, CloseFrame, PingFrame, ContinuationFrame, Opcode, TextFrame, BinaryFrame }
import spray.io.Pipeline
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.RawPipelineStage
import spray.can.client.ClientConnectionSettings
import spray.can.Http
import spray.http.HttpResponse
import spray.io.TickGenerator.Tick

object WebSocketFrontend {

  def apply(settings: ServerSettings, handler: ActorRef) = new RawPipelineStage[PipelineContext] {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      /**
       * proxy actor between handler and pipelines owner. It behaves as the sender
       * (instead of pipelines owner actor) which is telling handler:
       *
       *   HttpServerConnection(pipelines' owner) <-> receiverRef <-> handler
       *
       *   UHttpServerConnection
       *   +-------------------+  Frame Out     +---------+
       *   |                   | <-----------   |         |
       *   | WebSocketFrontend |                | Handler |
       *   |  (receiverRef)    | ----------->   |         |
       *   |-------------------|  Frame In      +---------+
       *   |  v             ^  |
       *   |  v             ^  |
       *   |-------------------|
       *   |                   |
       *   |                   |
       *   |      TCPIO        |
       *   +-------------------+
       *            ^
       *            |
       *            v
       *     +--------------+
       *     | client       |
       *     +--------------+
       *
       */
      private val actorContext = context.actorContext
      private val receiverRef = actorContext.actorOf(Props(new HandlerResponseReceiver)) // don't specify unique name

      val commandPipeline = commandPL

      val eventPipeline: EPL = {
        case FrameInEvent(frame: PingFrame)        => commandPL(FrameCommand(frame.copy(opcode = Opcode.Pong))) // auto bounce a pong frame
        case FrameInEvent(frame: CloseFrame)       => commandPL(FrameCommand(frame)) // auto bounce a close frame
        case FrameInEvent(_: ContinuationFrame)    => // We should have composed it during lower stage. Anyway, does not need to tell handler

        case FrameInEvent(frame)                   => commandPL(Pipeline.Tell(handler, frame, receiverRef))

        case UHttp.Upgraded                        => commandPL(Pipeline.Tell(handler, UHttp.Upgraded, receiverRef))
        case Http.MessageEvent(resp: HttpResponse) => commandPL(Pipeline.Tell(handler, resp, receiverRef))

        case ev: Tcp.ConnectionClosed =>
          commandPL(Pipeline.Tell(handler, ev, receiverRef))
          context.log.debug("{}", ev)
          eventPL(ev)

        case ev @ Tcp.CommandFailed(e: Tcp.Write) =>
          new FrameParser().onReceive(e.data.iterator) {
            case FrameParser.Success(frame @ TextFrame(payload)) =>
              context.log.warning("CommandFailed for Tcp.Write text frame: {} ...", payload.take(50).utf8String)
              commandPL(Pipeline.Tell(handler, FrameCommandFailed(frame, ev), receiverRef))
            case FrameParser.Success(frame @ BinaryFrame(payload)) =>
              context.log.warning("CommandFailed for Tcp.Write binary frame: {} ...", payload.take(50))
              commandPL(Pipeline.Tell(handler, FrameCommandFailed(frame, ev), receiverRef))
            case FrameParser.Success(frame @ ContinuationFrame(payload)) =>
              context.log.warning("CommandFailed for Tcp.Write continuation frame: {} ...", payload.take(50))
              commandPL(Pipeline.Tell(handler, FrameCommandFailed(frame, ev), receiverRef))
            case FrameParser.Success(frame) =>
              context.log.warning("CommandFailed for Tcp.Write frame: {}", frame.opcode)
              commandPL(Pipeline.Tell(handler, FrameCommandFailed(frame, ev), receiverRef))
            case x =>
              context.log.warning("CommandFailed for Tcp.Write: {} ...", e.data.take(50).utf8String)
              commandPL(Pipeline.Tell(handler, ev, receiverRef))
          }
          eventPL(ev)

        case Tick =>

        case ev =>
          commandPL(Pipeline.Tell(handler, ev, receiverRef))
          eventPL(ev)
      }

      /**
       * Receive handler's sending and wrap to Command, then put on the head of
       * context.actorContext.self's pipelines
       * TODO implement it as UnregisteredActorRef?
       */
      class HandlerResponseReceiver extends Actor {
        def receive = {
          // weird that this API accepts Frames and not *Command, like the server equivalent
          case x: Frame       => actorContext.self forward FrameCommand(x)
          case x: FrameStream => actorContext.self forward FrameStreamCommand(x)

          // to be consistent with the server
          case x: FrameCommand       => actorContext.self forward x
          case x: FrameStreamCommand => actorContext.self forward x

          case x @ Tcp.Close => actorContext.self forward x

          case w: Tcp.Command =>
            // assume client knows what they are doing (acking / nacking / error handling)
            actorContext.self forward w
        }
      }

    }

  }

  def apply(settings: ClientConnectionSettings, handler: ActorRef): RawPipelineStage[PipelineContext] = apply(null: ServerSettings, handler)

}
