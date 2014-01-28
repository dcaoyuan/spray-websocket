package spray.can.websocket

import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.DataFrame
import spray.can.websocket.frame.Frame
import spray.can.websocket.frame.Opcode
import spray.can.websocket.frame.PingFrame
import spray.can.websocket.frame.PongFrame
import spray.io.PipelineContext
import spray.io.PipelineStage
import spray.io.Pipelines

object FrameComposing {

  def apply(messageSizeLimit: Long) = new PipelineStage {
    def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {

      var fragmentFrames: List[Frame] = Nil // TODO as an interface that can be adapted to outside cache

      val commandPipeline = commandPL

      val eventPipeline: EPL = {

        case FrameInEvent(x) if x.rsv != 0 =>
          closeWithReason(StatusCode.ProtocolError,
            "RSV MUST be 0 unless an extension is negotiated that defines meanings for non-zero values.")
          fragmentFrames = Nil

        case FrameInEvent(x @ DataFrame(fin, opcode, _)) =>
          if (fin) { // final data frame

            (fragmentFrames, opcode) match {

              case (Nil, Opcode.Continuation) =>
                closeWithReason(StatusCode.ProtocolError,
                  "Received a final continuation frame, but without previous fragment frame(s).")

              case (_ :: _, Opcode.Text | Opcode.Binary) =>
                closeWithReason(StatusCode.ProtocolError,
                  "Received a final text/binary frame, but there has been fragment frame(s) existed and not finished yet.")

              case _ => // (Nil, Opcode.Text | Opcode.Binary) | (_ :: _, Opcode.Continuation)
                if (fragmentFrames.foldLeft(x.payload.length)(_ + _.payload.length) > messageSizeLimit) {
                  closeWithReason(StatusCode.MessageTooBig,
                    "Received a message that is too big for it to process, message size should not exceed " + messageSizeLimit)
                } else {
                  val head :: tail = (x :: fragmentFrames).reverse
                  val finalFrame = tail.foldLeft(head) { (acc, cont) => acc.copy(payload = acc.payload ++ cont.payload) }
                  if (finalFrame.opcode == Opcode.Text && !UTF8Validator.isValidate(finalFrame.payload)) {
                    closeWithReason(StatusCode.InvalidFramePayloadData,
                      "non-UTF-8 [RFC3629] data within a text message.")
                  } else {
                    eventPL(FrameInEvent(finalFrame.copy(fin = true, payload = finalFrame.payload.compact)))
                  }
                }

            }
            fragmentFrames = Nil

          } else { // data frame to be continued

            (fragmentFrames, x.opcode) match {

              case (Nil, Opcode.Text | Opcode.Binary) =>
                fragmentFrames = x :: Nil

              case (_ :: _, Opcode.Text | Opcode.Binary) =>
                closeWithReason(StatusCode.ProtocolError,
                  "Expect a continuation frame, but received a text/binary frame.")
                fragmentFrames = Nil

              case (Nil, Opcode.Continuation) =>
                closeWithReason(StatusCode.ProtocolError,
                  "Received a continuation frame, but without previous fragment frame(s).")
                fragmentFrames = Nil

              case _ => // (_ :: _, Opcode.Continuation)
                fragmentFrames = x :: fragmentFrames

            }
          }

        // --- final control frames

        case FrameInEvent(CloseFrame(statusCode, reason)) =>
          closeWithReason(statusCode, reason)
          fragmentFrames = Nil

        case ev @ FrameInEvent(PingFrame(_) | PongFrame(_)) =>
          eventPL(ev)

        case ev =>
          eventPL(ev)
      }

      def closeWithReason(statusCode: StatusCode, reason: String = "") {
        closeWithReason(CloseFrame(statusCode, reason))
      }

      def closeWithReason(closeFrame: CloseFrame) {
        eventPL(FrameOutEvent(closeFrame))
      }
    }
  }

}