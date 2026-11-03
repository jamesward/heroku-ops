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

  private def rpc(method: String, params: String = "{}", token: Option[String] = Some(Secrets.mcpToken(master)), path: String = "/mcp") =
    val body = s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
    val headers = Headers("Content-Type", "application/json") ++ Headers("Accept", "application/json, text/event-stream") ++
      token.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
    for
      url <- ZIO.fromEither(URL.decode(path))
      response <- (OpsRoutes.routes ++ OpsMcp.routes).runZIO(Request.post(url, Body.fromString(body)).addHeaders(headers))
      text <- response.body.asString
    yield (response.status.code, text, response.headers.get("WWW-Authenticate"))

  private val seeded = ZIO.serviceWithZIO[Ops]: ops =>
    ops.state.update: s =>
      s.copy(watched = Map(app -> w)).record(app, List(Event.Request, Event.Observed(Signal.Crash, "State changed from up to crashed")), at)
        .released(app, Release(12, "Deploy abc", at.minusSeconds(600)))

  def spec = suite("MCP endpoint")(
    test("no token and a wrong token are rejected") {
      for
        none <- rpc("tools/list", token = None)
        wrong <- rpc("tools/list", token = Some("nope"))
        raw <- rpc("tools/list", token = Some(master))
      yield assertTrue(none._1 == 401, wrong._1 == 401, raw._1 == 401)
    },
    test("the secret-path mount needs no header and never answers 401 or a challenge") {
      val good = s"/${Secrets.mcpUrlToken(master)}"
      for
        listed <- rpc("tools/list", token = None, path = good)
        wrong <- rpc("tools/list", token = None, path = "/not-the-token")
        headerToken <- rpc("tools/list", token = None, path = s"/${Secrets.mcpToken(master)}")
        drain <- rpc("tools/list", token = None, path = "/drain")
        health <- (OpsRoutes.routes ++ OpsMcp.routes).runZIO(Request.get(URL(Path.root / "health")))
      yield assertTrue(
        listed._1 == 200, listed._2.contains("\"name\":\"list_apps\""),
        wrong._1 == 404, wrong._3.isEmpty,
        headerToken._1 == 404,
        drain._1 != 200,
        health.status.code == 200,
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
