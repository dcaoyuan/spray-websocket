package spray.can.websocket.frame

object StatusCode {

  /**
   * Reserved Status Code Ranges
   *
   * 0-999
   *
   * Status codes in the range 0-999 are not used.
   *
   * 1000-2999
   *
   * Status codes in the range 1000-2999 are reserved for definition by
   * this protocol, its future revisions, and extensions specified in a
   * permanent and readily available public specification.
   *
   * 3000-3999
   *
   * Status codes in the range 3000-3999 are reserved for use by
   * libraries, frameworks, and applications.  These status codes are
   * registered directly with IANA.  The interpretation of these codes
   * is undefined by this protocol.
   *
   * 4000-4999
   *
   * Status codes in the range 4000-4999 are reserved for private use
   * and thus can't be registered.  Such codes can be used by prior
   * agreements between WebSocket applications.  The interpretation of
   * these codes is undefined by this protocol.
   *
   * code != 1004 && code != 1005 && code != 1006
   */
  def isValidCloseCode(code: Short) = (
    (code >= 1000 && code <= 1003) ||
    (code >= 1007 && code <= 1011) ||
    (code >= 3000 && code <= 4999))

  /**
   * 1000 indicates a normal closure, meaning that the purpose for
   * which the connection was established has been fulfilled.
   */
  val NormalClosure = StatusCode(1000)

  /**
   * 1001 indicates that an endpoint is "going away", such as a server
   * going down or a browser having navigated away from a page.
   */
  val GoingAway = StatusCode(1001)

  /**
   * 1002 indicates that an endpoint is terminating the connection due
   * to a protocol error.
   */
  val ProtocolError = StatusCode(1002)

  /**
   * 1003 indicates that an endpoint is terminating the connection
   * because it has received a type of data it cannot accept (e.g., an
   * endpoint that understands only text data MAY send this if it
   * receives a binary message).
   */
  val UnacceptDataType = StatusCode(1003)

  /**
   * Reserved.  The specific meaning might be defined in the future.
   */
  val Reserved = StatusCode(1004)

  /**
   * 1005 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that no status
   * code was actually present.
   */
  val NoStatusReceived = StatusCode(1005)

  /**
   * 1006 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that the
   * connection was closed abnormally, e.g., without sending or
   * receiving a Close control frame.
   */
  val AbnormalClosure = StatusCode(1006)

  /**
   * 1007 indicates that an endpoint is terminating the connection
   * because it has received data within a message that was not
   * consistent with the type of the message (e.g., non-UTF-8 [RFC3629]
   * data within a text message).
   */
  val InvalidFramePayloadData = StatusCode(1007)

  /**
   * 1008 indicates that an endpoint is terminating the connection
   * because it has received a message that violates its policy.  This
   * is a generic status code that can be returned when there is no
   * other more suitable status code (e.g., 1003 or 1009) or if there
   * is a need to hide specific details about the policy.
   */
  val PolicyViolation = StatusCode(1008)

  /**
   * 1009 indicates that an endpoint is terminating the connection
   * because it has received a message that is too big for it to
   * process.
   */
  val MessageTooBig = StatusCode(1009)

  /**
   * 1010 indicates that an endpoint (client) is terminating the
   * connection because it has expected the server to negotiate one or
   * more extension, but the server didn't return them in the response
   * message of the WebSocket handshake.  The list of extensions that
   * are needed SHOULD appear in the /reason/ part of the Close frame.
   * Note that this status code is not used by the server, because it
   * can fail the WebSocket handshake instead.
   */
  val MandatoryExt = StatusCode(1010)

  /**
   * 1011 indicates that a server is terminating the connection because
   * it encountered an unexpected condition that prevented it from
   * fulfilling the request.
   */
  val InternalServerError = StatusCode(1011)

  /**
   * 1015 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that the
   * connection was closed due to a failure to perform a TLS handshake
   * (e.g., the server certificate can't be verified).
   */
  val TlsHandshake = StatusCode(1015)

}

final case class StatusCode(val code: Short) extends AnyVal {
  /**
   * to 2 bytes - network byte-order (big endian)
   */
  def toBytes = Array[Byte](((code >> 8) & 0xff).toByte, (code & 0xff).toByte)
}
