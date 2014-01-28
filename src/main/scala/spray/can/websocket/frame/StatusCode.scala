package spray.can.websocket.frame

object StatusCode {
  val statusCodes = Set(
    NormalClosure,
    GoingAway,
    ProtocolError,
    UnacceptDataType,
    NoStatusReceived,
    AbnormalClosure,
    InvalidFramePayloadData,
    PolicyViolation,
    MessageTooBig,
    MandatoryExt,
    InternalServerError,
    TlsHandshake).map(x => x.code -> x).toMap

  def statusCodeFor(code: Short) = statusCodes.getOrElse(code, new StatusCode(code))

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
   */
  def isValidCloseCode(code: Short) = (
    statusCodes.contains(code) && code != 1005 && code != 1006 && code != 1015
    || (code >= 3000 && code < 5000))

  /**
   * 1000 indicates a normal closure, meaning that the purpose for
   * which the connection was established has been fulfilled.
   */
  case object NormalClosure extends StatusCode(1000)

  /**
   * 1001 indicates that an endpoint is "going away", such as a server
   * going down or a browser having navigated away from a page.
   */
  case object GoingAway extends StatusCode(1001)

  /**
   * 1002 indicates that an endpoint is terminating the connection due
   * to a protocol error.
   */
  case object ProtocolError extends StatusCode(1002)

  /**
   * 1003 indicates that an endpoint is terminating the connection
   * because it has received a type of data it cannot accept (e.g., an
   * endpoint that understands only text data MAY send this if it
   * receives a binary message).
   */
  case object UnacceptDataType extends StatusCode(1003)

  /**
   * Reserved.  The specific meaning might be defined in the future.
   */
  case object Reserved extends StatusCode(1004)

  /**
   * 1005 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that no status
   * code was actually present.
   */
  case object NoStatusReceived extends StatusCode(1005)

  /**
   * 1006 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that the
   * connection was closed abnormally, e.g., without sending or
   * receiving a Close control frame.
   */
  case object AbnormalClosure extends StatusCode(1006)

  /**
   * 1007 indicates that an endpoint is terminating the connection
   * because it has received data within a message that was not
   * consistent with the type of the message (e.g., non-UTF-8 [RFC3629]
   * data within a text message).
   */
  case object InvalidFramePayloadData extends StatusCode(1007)

  /**
   * 1008 indicates that an endpoint is terminating the connection
   * because it has received a message that violates its policy.  This
   * is a generic status code that can be returned when there is no
   * other more suitable status code (e.g., 1003 or 1009) or if there
   * is a need to hide specific details about the policy.
   */
  case object PolicyViolation extends StatusCode(1008)

  /**
   * 1009 indicates that an endpoint is terminating the connection
   * because it has received a message that is too big for it to
   * process.
   */
  case object MessageTooBig extends StatusCode(1009)

  /**
   * 1010 indicates that an endpoint (client) is terminating the
   * connection because it has expected the server to negotiate one or
   * more extension, but the server didn't return them in the response
   * message of the WebSocket handshake.  The list of extensions that
   * are needed SHOULD appear in the /reason/ part of the Close frame.
   * Note that this status code is not used by the server, because it
   * can fail the WebSocket handshake instead.
   */
  case object MandatoryExt extends StatusCode(1010)

  /**
   * 1011 indicates that a server is terminating the connection because
   * it encountered an unexpected condition that prevented it from
   * fulfilling the request.
   */
  case object InternalServerError extends StatusCode(1011)

  /**
   * 1015 is a reserved value and MUST NOT be set as a status code in a
   * Close control frame by an endpoint.  It is designated for use in
   * applications expecting a status code to indicate that the
   * connection was closed due to a failure to perform a TLS handshake
   * (e.g., the server certificate can't be verified).
   */
  case object TlsHandshake extends StatusCode(1015)

}

class StatusCode(val code: Short)
