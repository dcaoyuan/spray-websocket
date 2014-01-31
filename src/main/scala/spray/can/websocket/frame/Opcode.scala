package spray.can.websocket.frame

object Opcode {
  val Continuation = Opcode(0x0)
  val Text = Opcode(0x1)
  val Binary = Opcode(0x2)

  val Close = Opcode(0x8)
  val Ping = Opcode(0x9)
  val Pong = Opcode(0xA)
}

final case class Opcode(val code: Byte) extends AnyVal {
  def isReserved = code >= 0x3 && code <= 0x7 || code >= 0xB && code <= 0xF
  def isInvalid = code < 0 || code > 0xF
  def isControl = code >= 0x8
}
