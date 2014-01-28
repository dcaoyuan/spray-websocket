package spray.can.websocket

/**
 * A variant implementation of argrithm of "Flexible and Economical UTF-8 Decoder"
 * by Bjorn Hohrmann @see http://bjoern.hoehrmann.de/utf-8/decoder/dfa/
 */
import akka.util.ByteString

object UTF8Validator {
  private val types = Array[Byte](
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 00..0f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 10..1f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 20..2f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 30..3f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 40..4f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 50..5f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 60..6f
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 70..7f
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 80..8f
    9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, // 90..9f
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, // a0..af
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, // b0..bf
    8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // c0..cf
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // d0..df
    10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, // e0..ef
    11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8) // f0..ff

  private val states = Array[Byte](
    0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24,
    12, 12, 12, 12, 12, 24, 12, 24, 12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12,
    12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12,
    12, 12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12)

  private val ACCEPT = 0
  private val REJECT = 12

  /**
   * TODO it seems that applying directly on ByteString(i) won't work, why?
   */
  def isValidate(bytes: ByteString): Boolean = isValidate(bytes.toArray)
  @inline def isValidate(bytes: Array[Byte]): Boolean = {
    var state = ACCEPT
    var codep = 0
    var i = 0
    while (i < bytes.length && state != REJECT) {
      val b = bytes(i)
      val t = types(b & 0xff)
      codep = if (state != ACCEPT)
        (b & 0x3f) | (codep << 6)
      else
        (0xff >> t) & b

      state = states(state + t)

      i += 1
    }
    state == ACCEPT
  }

}