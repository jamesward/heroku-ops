package herokuops

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.chaining.*

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
    test("/health lists incidents that could not be delivered in the last day but stays 200; /alarm is a 503 only for the last 30 minutes") {
      def get(path: String) = OpsRoutes.routes.runZIO(Request.get(URL(Path.root / path)))
      for
        _ <- setup
        now <- Clock.instant
        undelivered = Delivery.Undelivered("no FIRE_TOKEN_WEBJARS config var")
        recent = IncidentRecord(now.minusSeconds(60), app, List(Reason(Signal.Memory, 5)), Nil, undelivered)
        hours = IncidentRecord(now.minusSeconds(3L * 3600), app, List(Reason(Signal.Crash, 1)), Nil, undelivered)
        old = IncidentRecord(now.minusSeconds(3L * 24 * 3600), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Undelivered("old"))
        fired = IncidentRecord(now.minusSeconds(60), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Fired("session_x"))
        state <- ZIO.serviceWith[Ops](_.state)
        _ <- state.update(_.incident(old).incident(fired))
        quiet <- get("alarm")
        _ <- state.update(_.incident(hours))
        stillQuiet <- get("alarm")
        health1 <- get("health")
        _ <- state.update(_.incident(recent))
        alarming <- get("alarm")
        alarmBody <- alarming.body.asString
        health2 <- get("health")
        healthBody <- health2.body.asString
      yield assertTrue(
        quiet.status.code == 200, stillQuiet.status.code == 200, health1.status.code == 200,
        alarming.status.code == 503, alarmBody.contains("memory x5"), alarmBody.contains("no FIRE_TOKEN_WEBJARS config var"), !alarmBody.contains("crash"),
        health2.status.code == 200, healthBody.contains("\"status\":\"degraded\""), healthBody.contains("crash x1"), !healthBody.contains("\"old\""),
      )
    },
    test("every log entry made while a request is handled carries its X-Request-ID as request_id, and none is invented") {
      def rejected(requestId: Option[String]) =
        val r = Request.post(URL(Path.root / "drain").addQueryParam("app", app.value), Body.fromString("")).addHeader(Header.Authorization.Basic("drain", "wrong"))
        HerokuOps.app.runZIO(requestId.fold(r)(id => r.addHeader("X-Request-ID", id)))
      for
        _ <- setup
        _ <- rejected(Some("f9ed4675-f1c5-3513-c61a-3b3b4e25b4c0"))
        _ <- rejected(None)
        out <- ZTestLogger.logOutput
        seen = out.filter(_.message().startsWith("drain rejected")).map(_.annotations.get("request_id"))
      yield assertTrue(seen == Chunk(Some("f9ed4675-f1c5-3513-c61a-3b3b4e25b4c0"), None))
    }.provideSomeLayer[Ops & Scope](ZLayer.succeed(Settings("http://unused", master))),
    test("the first request tells the app its own address; later ones don't change it") {
      def at(host: String, proto: Option[String]) =
        Request.get(URL(Path.root / "health")).addHeader("Host", host).pipe(r => proto.fold(r)(p => r.addHeader("X-Forwarded-Proto", p)))
      for
        ops <- ZIO.service[Ops]
        before <- ops.ownUrl.get
        _ <- OpsRoutes.routes.runZIO(at("jw-heroku-ops-bd1ee276f8be.herokuapp.com", Some("https")))
        first <- ops.ownUrl.get
        _ <- OpsRoutes.routes.runZIO(at("elsewhere.example.com", Some("https")))
        second <- ops.ownUrl.get
      yield assertTrue(
        before.isEmpty, first.contains("https://jw-heroku-ops-bd1ee276f8be.herokuapp.com"), second == first,
        Alarm.ownUrl(Some("a.b-c.com:8080"), None).contains("http://a.b-c.com:8080"),
        Alarm.ownUrl(Some("evil.com/x?y"), Some("https")).isEmpty, Alarm.ownUrl(Some("a.com"), Some("javascript")).contains("http://a.com"),
        Alarm.ownUrl(None, Some("https")).isEmpty,
      )
    },
    test("alarmTick requests the app's own /alarm only while an undelivered incident is recent") {
      for
        hits <- Ref.make(0)
        stub = Routes(Method.GET / "alarm" -> handler(hits.update(_ + 1).as(Response.status(Status.ServiceUnavailable))))
        port <- Server.install(stub)
        ops <- ZIO.service[Ops]
        _ <- ops.ownUrl.set(Some(s"http://localhost:$port"))
        active <- Ref.make(false)
        now <- Clock.instant
        _ <- ops.alarmTick(active)
        before <- hits.get
        _ <- ops.state.update(_.incident(IncidentRecord(now.minusSeconds(10), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Undelivered("no token"))))
        _ <- ops.alarmTick(active) *> ops.alarmTick(active)
        during <- hits.get
        on <- active.get
        _ <- ops.state.update(_.copy(incidents = Vector.empty))
        _ <- ops.alarmTick(active)
        after <- hits.get
        off <- active.get
        noUrl <- Ref.make(false)
        _ <- ops.state.update(_.incident(IncidentRecord(now, app, Nil, Nil, Delivery.Undelivered("x"))))
        _ <- ops.ownUrl.set(None)
        _ <- ops.alarmTick(noUrl)
        withoutUrl <- hits.get
      yield assertTrue(before == 0, during == 2, on, after == 2, !off, withoutUrl == 2)
    }.provideSomeLayer[Ops](ZLayer.succeed(Server.Config.default.onAnyOpenPort) >>> Server.live),
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
