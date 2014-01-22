package spray.can.websocket.frame

object Opcode {
  case object Continuation extends Opcode(0x0)
  case object Text extends Opcode(0x1)
  case object Binary extends Opcode(0x2)

  case object Close extends Opcode(0x8)
  case object Ping extends Opcode(0x9)
  case object Pong extends Opcode(0xA)

  final case class Reserved(_value: Byte) extends Opcode(_value)
  case object Invalid extends Opcode(-1)

  private val codes = Array(
    Continuation, Text, Binary, Reserved(0x3), Reserved(0x4), Reserved(0x5), Reserved(0x6), Reserved(0x7),
    Close, Ping, Pong, Reserved(0xB), Reserved(0xC), Reserved(0xD), Reserved(0xE), Reserved(0xF))

  def opcodeFor(value: Int): Opcode = if (value >= 0 && value < codes.length) codes(value) else Invalid
}
abstract class Opcode(val value: Byte) {
  def isControl = value >= 0x8
  def isInvalid = this match {
    case _: Opcode.Reserved | Opcode.Invalid => true
    case _                                   => false
  }
}
