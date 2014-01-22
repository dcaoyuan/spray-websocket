package spray.can.websocket.frame

import akka.util.ByteIterator
import akka.util.ByteString
import scala.annotation.tailrec

object FrameParser {

  /**
   * nBytes   expected number of bytes of this state
   */
  sealed trait State { def nBytes: Long }
  case object ExpectFin extends State { def nBytes = 1 }
  case object ExpectMasked extends State { def nBytes = 1 }
  final case class ExpectPayloadLen(nBytes: Long) extends State
  case object ExpectMaskingKey extends State {
    def nBytes = 4
    def unapply(x: this.type): Option[Long] = Some(x.nBytes)
  }
  final case class ExpectData(nBytes: Long) extends State
  final case class Success(frame: Frame) extends State { def nBytes = 0 }
  case object InvalidOp extends State { def nBytes = 0 }
  case object Oversized extends State { def nBytes = 0 }

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
final class FrameParser(frameSizeLimit: Long) {
  import Frame._
  import FrameParser._

  private var fin: Boolean = _
  private var rsv: Byte = _
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
      case x @ (InvalidOp | Oversized) => // with error, should drop remaining input
        input = ByteString.empty.iterator
        stateListener(x)
        ExpectFin

      case x: Success =>
        stateListener(x)
        ExpectFin

      case ExpectData(0) => // should finish a frame right now too.
        stateListener(Success(Frame(fin, rsv, opcode, ByteString.empty)))
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
      val b0 = input.next()
      fin = ((b0 >> 7) & 1) == 1
      rsv = ((b0 >> 4) & 7).toByte

      opcode = Opcode.opcodeFor(b0 & 0xf)
      if (opcode.isInvalid) {
        InvalidOp
      } else {
        ExpectMasked
      }

    case ExpectMasked =>
      val b1 = input.next()
      isMasked = ((b1 >> 7) & 1) == 1

      (b1 & 127) match {
        case 126 => ExpectPayloadLen(2)
        case 127 => ExpectPayloadLen(8)
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

      Success(Frame(fin, rsv, opcode, ByteString(payload)))

    case x => x
  }

  private def parsePayloadLen(input: ByteIterator, nBytes: Long, l: Int = -1): State = {
    payloadLen = nBytes match {
      case 0 => l // length has been got
      case 2 => input.getShort & 0xffff // to unsigned int
      case 8 => input.getLong
    }

    if (payloadLen > frameSizeLimit) {
      Oversized
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
