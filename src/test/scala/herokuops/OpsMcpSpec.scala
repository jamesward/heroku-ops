package herokuops

import zio.*
import zio.http.*
import zio.test.*

import java.time.Instant

object OpsMcpSpec extends ZIOSpecDefault:
  private val master = "test-master"
  private val app = AppName.parse("webjars").toOption.get
  private val w = Watched(app, "webjars/webjars", "https://www.webjars.org", "/", None)
  private val at = Instant.parse("2026-11-03T05:41:30Z")

  private val settings = ZLayer.succeed(Settings("http://unused", master))
  private val layer: ZLayer[Any, Throwable, Ops & Settings] = settings ++ (settings ++ Client.default >>> Ops.live)

  private def rpc(method: String, params: String = "{}", token: Option[String] = Some(Secrets.mcpToken(master))) =
    val body = s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
    val headers = Headers("Content-Type", "application/json") ++ Headers("Accept", "application/json, text/event-stream") ++
      token.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
    for
      url <- ZIO.fromEither(URL.decode("/mcp"))
      response <- OpsMcp.routes.runZIO(Request.post(url, Body.fromString(body)).addHeaders(headers))
      text <- response.body.asString
    yield (response.status.code, text, response.headers.get("WWW-Authenticate"))

  private val initParams = """{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}"""

  private val seeded = ZIO.serviceWithZIO[Ops]: ops =>
    ops.state.update: s =>
      s.copy(watched = Map(app -> w)).record(app, List(Event.Request, Event.Observed(Signal.Crash, "State changed from up to crashed")), at)
        .released(app, Release(12, "Deploy abc", at.minusSeconds(600)))

  def spec = suite("MCP endpoint")(
    test("without a token the handshake answers like a plain server and the tools need the token: a JSON-RPC error, never a 401") {
      for
        init <- rpc("initialize", initParams, token = None)
        list <- rpc("tools/list", token = None)
        wrong <- rpc("initialize", initParams, token = Some("nope"))
        raw <- rpc("tools/list", token = Some(master))
      yield assertTrue(
        init._1 == 200, init._2.contains("\"serverInfo\""), init._2.contains("\"result\""), init._3.isEmpty,
        list._1 == 200, list._2.contains("\"code\":-32001"), !list._2.contains("\"tools\""), list._3.isEmpty,
        wrong._1 == 200, wrong._2.contains("was not accepted"), wrong._3.isEmpty,
        raw._1 == 200, raw._2.contains("\"code\":-32001"), !raw._2.contains("\"tools\""),
      )
    },
    test("with the derived token it lists the read-only tools") {
      for
        (code, text, _) <- rpc("tools/list")
      yield assertTrue(
        code == 200,
        List("list_apps", "app_status", "recent_events", "timeseries", "incidents", "releases", "probe_now", "alert_rules").forall(n => text.contains(s""""name":"$n"""")),
        text.contains("readOnlyHint"),
      )
    },
    test("list_apps and recent_events answer from the state") {
      for
        _ <- seeded
        apps <- rpc("tools/call", """{"name":"list_apps","arguments":{}}""")
        events <- rpc("tools/call", """{"name":"recent_events","arguments":{"app":"webjars"}}""")
        unknown <- rpc("tools/call", """{"name":"app_status","arguments":{"app":"nope"}}""")
      yield assertTrue(
        apps._2.contains("\"app\":\"webjars\""), apps._2.contains("Deploy abc"),
        events._2.contains("crashed"),
        unknown._2.contains("isError\":true"), unknown._2.contains("watched apps: webjars"),
      )
    },
  ).provide(layer, Scope.default)
