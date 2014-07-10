package spray.can.server

import spray.io._
import spray.http.HttpHeaders.RawHeader
import spray.http.{ ChunkedRequestStart, HttpRequest }

private object RemoteIpPortHeaderSupport extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val raHeader = RawHeader("Remote-Ip-Port", context.remoteAddress.getAddress.getHostAddress + ":" + context.remoteAddress.getPort.toString)
      def appendHeader(request: HttpRequest): HttpRequest = request.mapHeaders(raHeader :: _)

      val commandPipeline = commandPL

      val eventPipeline: EPL = {
        case x: RequestParsing.HttpMessageStartEvent ⇒ eventPL {
          x.copy(
            messagePart = x.messagePart match {
              case request: HttpRequest         ⇒ appendHeader(request)
              case ChunkedRequestStart(request) ⇒ ChunkedRequestStart(appendHeader(request))
              case _                            ⇒ throw new IllegalStateException
            })
        }

        case ev ⇒ eventPL(ev)
      }
    }
}
