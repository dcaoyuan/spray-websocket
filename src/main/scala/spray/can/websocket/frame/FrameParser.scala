package spray.can.websocket.frame

import akka.util.ByteIterator
import akka.util.ByteString
import scala.annotation.tailrec

object FrameParser {
  var frameSizeLimit: Long = Long.MaxValue

  /**
   * nBytes   expected number of bytes of this state
   */
  sealed trait State { def nBytes: Long }

  case object ExpectFin extends State { def nBytes = 1 }
  case object ExpectMasked extends State { def nBytes = 1 }

  object ExpectPayloadLen {
    def unapply(x: ExpectPayloadLen): Option[Long] = Some(x.nBytes)
  }
  sealed trait ExpectPayloadLen extends State
  case object ExpectShortPayloadLen extends ExpectPayloadLen { def nBytes = 2 }
  case object ExpectLongPayloadLen extends ExpectPayloadLen { def nBytes = 8 }

  case object ExpectMaskingKey extends State {
    def nBytes = 4
    def unapply(x: this.type): Option[Long] = Some(x.nBytes)
  }
  final case class ExpectData(nBytes: Long) extends State

  final case class Success(frame: Frame) extends State { def nBytes = 0 }

  object Failure {
    def unapply(x: Failure): Option[(StatusCode, String)] = Some(x.statusCode, x.reason)
  }
  sealed abstract class Failure(val statusCode: StatusCode, val reason: String) extends State { def nBytes = 0 }
  case object InvalidOpcode extends Failure(StatusCode.ProtocolError, "Invalid or reserved opcode.")
  case object FalseFinControlFrame extends Failure(StatusCode.ProtocolError, "Receive control frame with false fin.")
  case object OversizedControlFrame extends Failure(StatusCode.ProtocolError, "All control frames MUST have a payload length of 125 bytes or less and MUST NOT be fragmented.")
  case object OversizedDataFrame extends Failure(StatusCode.MessageTooBig, "Received a message that is too big for it to process, message size should not exceed " + frameSizeLimit)
}

/*-
   0               1               2               3
   0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7|0 1 2 3 4 5 6 7
  +-+-+-+-+-------+-+-------------+-------------------------------+
  |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
  |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
  |N|V|V|V|       |S|             |   (if payload len==126/127)   |
  | |1|2|3|       |K|             |                               |
  +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
  |     Extended payload length continued, if payload len == 127  |
  + - - - - - - - - - - - - - - - +-------------------------------+
  |                               |Masking-key, if MASK set to 1  |
  +-------------------------------+-------------------------------+
  | Masking-key (continued)       |          Payload Data         |
  +-------------------------------- - - - - - - - - - - - - - - - +
  :                     Payload Data continued ...                :
  + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
  |                     Payload Data continued ...                |
  +---------------------------------------------------------------+
 */
final class FrameParser {
  import Frame._
  import FrameParser._

  private var finRsvOp: Byte = _
  private var fin: Boolean = _
  private var opcode: Opcode = _
  private var isMasked: Boolean = _
  private var maskingKey: Array[Byte] = Array.empty
  private var payloadLen: Long = _

  // states that may need to reset before next frame
  private var state: State = ExpectFin
  private var input: ByteIterator = ByteString.empty.iterator

  /**
   * ByteIterator.++(that) seems will cut left, we have to define an ugly custom one
   */
  private def concat(left: ByteIterator, right: ByteIterator): ByteIterator = {
    if (right.len == 0) {
      left
    } else {
      val leftLen = left.len
      if (leftLen == 0) {
        right
      } else {
        val ls = Array.ofDim[Byte](leftLen)
        left.copyToArray(ls, 0, leftLen)
        val rs = right.toArray
        ByteString(ls ++ rs).iterator
      }
    }
  }

  def onReceive(newInput: ByteIterator)(stateListener: PartialFunction[State, Unit]) {
    input = concat(input, newInput)
    process()(stateListener)
  }

  @tailrec
  private def process()(stateListener: PartialFunction[State, Unit]) {
    // has enough data? if false, wait for more input
    if (input.len < state.nBytes) {
      return
    }

    // parse and see if we've finished a frame, notice listener and reset state if true
    state = parse(input, state) match {
      case x: Failure => // with error, should drop remaining input
        input = ByteString.empty.iterator
        stateListener(x)
        ExpectFin

      case x: Success =>
        stateListener(x)
        ExpectFin

      case ExpectData(0) => // should finish a frame right now too.
        stateListener(Success(Frame(finRsvOp, ByteString.empty)))
        ExpectFin

      case x => x
    }

    // has more data? go on if true, else wait for more input
    if (input.hasNext) {
      process()(stateListener)
    }
  }

  private def parse(input: ByteIterator, state: State): State = state match {
    case ExpectFin =>
      finRsvOp = input.next()
      fin = finFrom(finRsvOp)

      opcode = opcodeFrom(finRsvOp)
      if (opcode.isInvalid || opcode.isReserved) {
        InvalidOpcode
      } else if (opcode.isControl && !fin) {
        FalseFinControlFrame
      } else {
        ExpectMasked
      }

    case ExpectMasked =>
      val b1 = input.next()
      isMasked = ((b1 >> 7) & 1) == 1

      (b1 & 127) match {
        case 126 => ExpectShortPayloadLen
        case 127 => ExpectLongPayloadLen
        case len => parsePayloadLen(input, 0, len)
      }

    case ExpectPayloadLen(n) =>
      parsePayloadLen(input, n)

    case ExpectMaskingKey(n) =>
      maskingKey = Array.ofDim[Byte](n.toInt)
      input.getBytes(maskingKey, 0, n.toInt)
      ExpectData(payloadLen)

    case ExpectData(n) =>
      val payload = Array.ofDim[Byte](n.toInt)
      input.getBytes(payload, 0, n.toInt)
      if (isMasked) {
        maskData(payload, maskingKey)
      }

      Success(Frame(finRsvOp, ByteString(payload)))

    case x => x
  }

  private def parsePayloadLen(input: ByteIterator, nBytes: Long, len: Int = -1): State = {
    payloadLen = nBytes match {
      case 0 => len // length has been got
      case 2 => input.getShort & 0xffff // to unsigned int
      case 8 => input.getLong
    }

    if (payloadLen > frameSizeLimit) {
      OversizedDataFrame
    } else if (opcode.isControl && payloadLen > 125) {
      OversizedControlFrame
    } else {
      if (isMasked) {
        ExpectMaskingKey
      } else {
        maskingKey = Array.empty
        ExpectData(payloadLen)
      }
    }
  }

}
