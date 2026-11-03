package herokuops

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object HerokuOpsSpec extends ZIOSpecDefault:
  private val app = AppName.parse("webjars").toOption.get
  private val master = "test-master"
  private val watched = Watched(app, "webjars/webjars", "https://www.webjars.org", "/", None)

  private val layer: ZLayer[Any, Throwable, Ops] =
    ZLayer.succeed(Settings("http://unused", master)) ++ Client.default >>> Ops.live

  private val crash = "<190>1 2026-11-03T05:41:00+00:00 host heroku web.1 - State changed from up to crashed"
  private def framed(msg: String) = s"${msg.getBytes(UTF_8).length} $msg".getBytes(UTF_8)
  private def basic(secret: String) = "Basic " + Base64.getEncoder.encodeToString(s"drain:$secret".getBytes(UTF_8))

  private def post(path: String, body: Array[Byte], headers: Headers = Headers.empty) =
    for
      url <- ZIO.fromEither(URL.decode(path))
      response <- OpsRoutes.routes.runZIO(Request.post(url, Body.fromArray(body)).addHeaders(headers))
    yield response

  private val setup = ZIO.serviceWithZIO[Ops](_.state.update(_.copy(watched = Map(app -> watched))))
  private val drainSecret = Secrets.derive(master, Secrets.Kind.Drain, app)

  def spec = suite("heroku-ops")(
    test("GET /health reports ok and the watched app count") {
      for
        _ <- setup
        response <- OpsRoutes.routes.runZIO(Request.get(URL(Path.root / "health")))
        body <- response.body.asString
      yield assertTrue(response.status.code == 200, body.contains("\"status\":\"ok\""), body.contains("\"watchedApps\":1"))
    },
    test("DRAIN_STATUS (and DRAIN_RETRY_AFTER) make an accepted batch answer with that status, after processing it like any other") {
      val auth = Header.Authorization.Basic("drain", Secrets.derive(master, Secrets.Kind.Drain, app))
      val line = "<190>1 2026-11-03T20:00:00+00:00 host heroku router - at=info method=GET path=\"/\" host=x request_id=r fwd=\"1.2.3.4\" dyno=web.1 connect=0ms service=5ms status=200 bytes=10 protocol=https"
      val req = Request.post(URL(Path.root / "drain").addQueryParam("app", app.value), Body.fromString(s"${line.length} $line")).addHeader(auth)
      for
        _ <- setup
        ops <- ZIO.service[Ops]
        failing = OpsRoutes.routes.provideEnvironment(ZEnvironment(ops.copy(settings = ops.settings.copy(drainStatus = Status.ServiceUnavailable, drainRetryAfter = Some("30")))))
        res <- failing.runZIO(req)
        s <- ops.state.get
      yield assertTrue(res.status.code == 503, res.rawHeader("Retry-After").contains("30"), s.stats.drainBatches == 1L, s.stats.logLines == 1L)
    },
    test("/health is a 503 listing incidents that could not be delivered in the last day, and 200 again after that") {
      for
        _ <- setup
        now <- Clock.instant
        fresh = IncidentRecord(now.minusSeconds(60), app, List(Reason(Signal.Memory, 5)), Nil, Delivery.Undelivered("no FIRE_TOKEN_WEBJARS config var"))
        old = IncidentRecord(now.minusSeconds(3L * 24 * 3600), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Undelivered("old"))
        fired = IncidentRecord(now.minusSeconds(60), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Fired("session_x"))
        _ <- ZIO.serviceWithZIO[Ops](_.state.update(_.incident(old).incident(fired)))
        ok <- OpsRoutes.routes.runZIO(Request.get(URL(Path.root / "health")))
        _ <- ZIO.serviceWithZIO[Ops](_.state.update(_.incident(fresh)))
        degraded <- OpsRoutes.routes.runZIO(Request.get(URL(Path.root / "health")))
        body <- degraded.body.asString
      yield assertTrue(
        ok.status.code == 200,
        degraded.status.code == 503,
        body.contains("\"status\":\"degraded\""), body.contains("no FIRE_TOKEN_WEBJARS config var"), body.contains("memory x5"), !body.contains("\"old\""),
      )
    },
    test("a drain from an unknown app is a 404") {
      for
        _ <- setup
        response <- post("/drain?app=nope-app", framed(crash))
      yield assertTrue(response.status.code == 404)
    },
    test("a drain with the wrong secret is a 401 and nothing is recorded") {
      for
        _ <- setup
        response <- post("/drain?app=webjars", framed(crash), Headers(Header.Authorization.Unparsed("Basic", "eHg6eHg=")))
        s <- ZIO.serviceWithZIO[Ops](_.state.get)
      yield assertTrue(response.status.code == 401, s.stats.rejected == 1, s.apps.isEmpty)
    },
    test("an authorized drain is recorded and a crash line raises an alert") {
      for
        _ <- setup
        response <- post("/drain?app=webjars", framed(crash), Headers(Header.Authorization.Unparsed("Basic", basic(drainSecret).drop(6))))
        s <- ZIO.serviceWithZIO[Ops](_.state.get)
        at <- Clock.instant
      yield assertTrue(
        response.status.code == 204,
        s.stats.logLines == 1,
        Detector.evaluate(app, s.app(app), at)._2.exists(_.reasons.map(_.signal) == List(Signal.Crash)),
      )
    },
    test("a webhook needs a valid signature") {
      val payload = """{"resource":"release","action":"update","data":{"version":3,"status":"failed","description":"d"}}""".getBytes(UTF_8)
      val secret = Secrets.derive(master, Secrets.Kind.Hook, app)
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")
      mac.init(javax.crypto.spec.SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
      val sig = Base64.getEncoder.encodeToString(mac.doFinal(payload))
      for
        _ <- setup
        bad <- post("/hook?app=webjars", payload, Headers("Heroku-Webhook-Hmac-SHA256", "nope"))
        good <- post("/hook?app=webjars", payload, Headers("Heroku-Webhook-Hmac-SHA256", sig))
        s <- ZIO.serviceWithZIO[Ops](_.state.get)
        at <- Clock.instant
      yield assertTrue(
        bad.status.code == 401,
        good.status.code == 204,
        Detector.evaluate(app, s.app(app), at)._2.exists(_.reasons.map(_.signal) == List(Signal.ReleaseFailed)),
      )
    },
  ).provide(layer, Scope.default)
