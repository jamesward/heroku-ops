package herokuops

import com.jamesward.ziohttp.mcp.JsonRpcMessage
import zio.*
import zio.http.*
import zio.json.*

/** One log line per MCP request: the HTTP method, the JSON-RPC method (and the tool name for a tool call), whether a bearer was
  * sent (never its value), and the client's user agent. Router lines show only the status; this shows what a client asked, which
  * is how a connector probe (claude.ai) can be understood. */
object McpRequestLog:
  def line(httpMethod: String, userAgent: Option[String], hasAuthorization: Boolean, protocol: Option[String], body: String): String =
    val (rpc, id, tool) = body.fromJson[JsonRpcMessage] match
      case Right(JsonRpcMessage.Request(id, method, params)) => (method, id.toString, params.flatMap(_.get("name")).flatMap(_.asString))
      case Right(JsonRpcMessage.Notification(method, _))      => (method, "-", None)
      case Left(_)                                            => (if body.isBlank then "-" else "unparsable", "-", None)
    val clean = (s: String) => s.replaceAll("[\\p{Cntrl}\\s]+", " ").take(80)
    s"mcp http=$httpMethod rpc=$rpc id=${clean(id)} tool=${tool.map(clean).getOrElse("-")} auth=${if hasAuthorization then "sent" else "none"} " +
      s"protocol=${protocol.map(clean).getOrElse("-")} ua=${userAgent.map(clean).getOrElse("-")}"

  /** Outermost on the MCP routes, so it sees the requests the bearer check refuses too. Reads the body, which streaming-disabled
    * servers (see HerokuOps) keep in memory. */
  val aspect: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request]: req =>
      req.body.asString.orElseSucceed("").flatMap: body =>
        ZIO.logInfo(line(req.method.name, req.rawHeader("User-Agent"), req.rawHeader("Authorization").isDefined, req.rawHeader("MCP-Protocol-Version"), body))
          .as((req, ())))
