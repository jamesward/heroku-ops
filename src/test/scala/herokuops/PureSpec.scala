package herokuops

import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.chaining.*

object PureSpec extends ZIOSpecDefault:
  private def frame(msg: String): String = s"${msg.getBytes(UTF_8).length} $msg"
  private def body(msgs: String*): Array[Byte] = msgs.map(frame).mkString.getBytes(UTF_8)
  private val router = "<158>1 2026-11-03T05:41:00+00:00 host heroku router - "
  private val app = AppName.parse("webjars").toOption.get

  private val factory =
    """### Watched Heroku apps
      |
      || App | Repo | URL | Probe |
      ||---|---|---|---|
      || webjars | webjars/webjars | https://www.webjars.org | / |
      |
      |### Next
      || `webjars/webjars` | Wed | 06:47 | `trig_0129h28dMtfdoGpjCShqSAW4` |
      |""".stripMargin

  def spec = suite("pure parts")(
    suite("FactoryTable")(
      test("reads watched apps and the app's routine id") {
        val w = FactoryTable.watched(factory).toOption.get
        assertTrue(w.map(_.app.value) == List("webjars"), w.head.routine.map(_.value) == Some("trig_0129h28dMtfdoGpjCShqSAW4"))
      },
      test("rejects a bad app name") {
        assertTrue(FactoryTable.watched(factory.replace("| webjars |", "| Web Jars |")).isLeft)
      },
    ),
    suite("LogFrames")(
      test("parses octet counted frames") {
        val msgs = LogFrames.parse(body(router + "at=info method=GET path=\"/\" status=200", "<190>1 2026-11-03T05:41:00+00:00 host app web.1 - hello\nworld"))
        assertTrue(msgs.map(_.proc) == List("router", "web.1"), msgs.last.text == "hello\nworld", msgs.head.source == "heroku")
      },
      test("stops at malformed input and keeps what it parsed") {
        val good = body(router + "at=info method=GET status=200")
        assertTrue(LogFrames.parse(good ++ "999 truncated".getBytes(UTF_8)).size == 1, LogFrames.parse("nonsense".getBytes(UTF_8)).isEmpty)
      },
    ),
    suite("Classifier")(
      test("router timeout, crash and 5xx") {
        def sig(t: String) = Classifier.classify(LogMessage("heroku", "router", t)).collect { case Event.Observed(s, _) => s }
        assertTrue(
          sig("at=error code=H12 desc=\"Request timeout\" method=GET path=\"/x\" status=503") == List(Signal.Timeout),
          sig("at=error code=H10 desc=\"App crashed\" method=GET path=\"/\" status=503") == List(Signal.Crash),
          sig("at=error code=H13 method=GET path=\"/\"") == List(Signal.RouterError),
          sig("at=error code=H21 method=GET path=\"/\"") == List(Signal.RouterError),
          sig("at=error code=H15 desc=\"Idle connection\" method=GET path=\"/_kyo/ws\"").isEmpty,
          sig("at=error code=H27 method=GET path=\"/\"").isEmpty,
          sig("at=info method=GET path=\"/\" status=502") == List(Signal.ServerError),
          sig("at=info method=GET path=\"/\" status=200").isEmpty,
        )
      },
      test("every router request is counted") {
        assertTrue(Classifier.classify(LogMessage("heroku", "router", "at=info method=GET path=\"/\" status=200")) == List(Event.Request))
      },
      test("dyno memory and crash lines") {
        def sig(t: String) = Classifier.classify(LogMessage("heroku", "web.1", t)).collect { case Event.Observed(s, _) => s }
        assertTrue(
          sig("Error R14 (Memory quota exceeded)") == List(Signal.Memory),
          sig("Error R15 (Memory quota vastly exceeded)") == List(Signal.Crash),
          sig("State changed from up to crashed") == List(Signal.Crash),
          sig("Process exited with status 1").isEmpty,
          sig("Process exited with status 143").isEmpty,
          sig("Stopping all processes with SIGTERM").isEmpty,
          sig("State changed from starting to up").isEmpty,
        )
      },
      test("app errors, JSON or text") {
        def n(t: String) = Classifier.classify(LogMessage("app", "web.1", t)).size
        assertTrue(n("""{"level":"ERROR","message":"boom"}""") == 1, n("2026 ERROR something") == 1, n("""{"level":"INFO"}""") == 0)
      },
      test("samples drop query strings and control characters and are capped") {
        val s = Classifier.sample("at=info method=GET path=\"/a?token=secret\" x\u0007y" + "z" * 400)
        assertTrue(!s.contains("secret"), !s.contains('\u0007'), s.length == 300)
      },
    ),
    suite("Secrets")(
      test("derivation is stable and per app and kind") {
        val other = AppName.parse("javadocs").toOption.get
        val a = Secrets.derive("m", Secrets.Kind.Drain, app)
        assertTrue(a == Secrets.derive("m", Secrets.Kind.Drain, app), a != Secrets.derive("m", Secrets.Kind.Drain, other), a != Secrets.derive("m", Secrets.Kind.Hook, app))
      },
      test("drain basic auth") {
        val secret = Secrets.derive("m", Secrets.Kind.Drain, app)
        val ok = "Basic " + java.util.Base64.getEncoder.encodeToString(s"drain:$secret".getBytes(UTF_8))
        assertTrue(Secrets.drainAuthorized(Some(ok), secret), !Secrets.drainAuthorized(Some("Basic eHg6eHg="), secret), !Secrets.drainAuthorized(None, secret))
      },
      test("webhook signature, base64 or hex") {
        val payload = """{"resource":"release"}""".getBytes(UTF_8)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("s".getBytes(UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload)
        assertTrue(
          Secrets.webhookAuthorized(payload, Some(java.util.Base64.getEncoder.encodeToString(digest)), "s"),
          Secrets.webhookAuthorized(payload, Some(digest.map(b => f"${b & 0xff}%02x").mkString), "s"),
          !Secrets.webhookAuthorized(payload, Some("AAAA"), "s"),
          !Secrets.webhookAuthorized(payload, None, "s"),
        )
      },
    ),
    suite("Webhook")(
      test("a failed release, a successful release, a crashed dyno") {
        val at = Instant.parse("2026-11-03T05:41:00Z")
        def parse(s: String) = Webhook.parse(s.getBytes(UTF_8), at).toOption.get
        val failed = parse("""{"resource":"release","action":"update","id":"x","data":{"version":7,"status":"failed","description":"Deploy abc","other":1}}""")
        val ok = parse("""{"resource":"release","action":"update","data":{"version":8,"status":"succeeded","description":"Deploy def"}}""")
        val crash = parse("""{"resource":"dyno","action":"update","data":{"state":"crashed","type":"web"}}""")
        assertTrue(
          failed.collect { case HookResult.Observed(s, _) => s } == List(Signal.ReleaseFailed),
          ok.collect { case HookResult.Released(r) => r.version } == List(8),
          crash.collect { case HookResult.Observed(s, _) => s } == List(Signal.Crash),
          Webhook.parse("not json".getBytes(UTF_8), at).isLeft,
        )
      },
    ),
    suite("Detector")(
      test("a crash alerts once, then stays quiet during the cooldown") {
        val t0 = Instant.parse("2026-11-03T05:41:00Z")
        val s1 = Detector.record(Detector.empty, Event.Observed(Signal.Crash, "State changed from up to crashed"), t0)
        val (s2, a1) = Detector.evaluate(app, s1, t0)
        val (_, a2) = Detector.evaluate(app, s2, t0.plusSeconds(120))
        assertTrue(a1.exists(_.reasons.map(_.signal) == List(Signal.Crash)), a1.exists(_.samples.nonEmpty), a2.isEmpty)
      },
      test("timeouts need five in the window; old ones don't count") {
        val t0 = Instant.parse("2026-11-03T05:41:00Z")
        val four = (1 to 4).foldLeft(Detector.empty)((s, _) => Detector.record(s, Event.Observed(Signal.Timeout, "h12"), t0))
        val five = Detector.record(four, Event.Observed(Signal.Timeout, "h12"), t0)
        assertTrue(Detector.evaluate(app, four, t0)._2.isEmpty, Detector.evaluate(app, five, t0)._2.nonEmpty, Detector.evaluate(app, five, t0.plusSeconds(600))._2.isEmpty)
      },
      test("5xx needs both a count and a share of requests") {
        val t0 = Instant.parse("2026-11-03T05:41:00Z")
        def load(errors: Int, ok: Int) =
          val e = (1 to errors).foldLeft(Detector.empty)((s, _) => Detector.record(s, Event.Observed(Signal.ServerError, "502"), t0))
          (1 to ok).foldLeft(e)((s, _) => Detector.record(s, Event.Request, t0))
        assertTrue(
          Detector.evaluate(app, load(4, 10), t0)._2.isEmpty,
          Detector.evaluate(app, load(5, 1000), t0)._2.isEmpty,
          Detector.evaluate(app, load(10, 1000), t0)._2.nonEmpty,
          Detector.evaluate(app, load(5, 100), t0)._2.nonEmpty,
        )
      },
      test("three failed probes mean down; two good ones mean up") {
        val down = (1 to 3).foldLeft(Detector.empty)((s, _) => Detector.probe(s, false))
        val t0 = Instant.parse("2026-11-03T05:41:00Z")
        val up = (1 to 2).foldLeft(down)((s, _) => Detector.probe(s, true))
        assertTrue(down.down, Detector.evaluate(app, down, t0)._2.exists(_.reasons.map(_.signal) == List(Signal.ProbeDown)), !up.down)
      },
      test("a condition that never clears alerts once, then again after a day") {
        val t0 = Instant.parse("2026-11-03T00:00:00Z")
        val steps = (0 to 25 * 6).map(i => t0.plusSeconds(i * 600L))
        val fired = steps.foldLeft((Detector.empty, List.empty[Long])): (acc, at) =>
          val (s, hours) = acc
          val (next, alert) = Detector.evaluate(app, Detector.record(s, Event.Observed(Signal.Memory, "R14"), at)
            .pipe(Detector.record(_, Event.Observed(Signal.Memory, "R14"), at)).pipe(Detector.record(_, Event.Observed(Signal.Memory, "R14"), at)), at)
          (next, hours ++ alert.map(_ => (at.getEpochSecond - t0.getEpochSecond) / 3600))
        assertTrue(fired._2 == List(0L, 24L))
      },
      test("a condition that clears and returns alerts again only after the cooldown") {
        val t0 = Instant.parse("2026-11-03T05:00:00Z")
        def crash(s: AppState, min: Long) = Detector.record(s, Event.Observed(Signal.Crash, "c"), t0.plusSeconds(min * 60))
        def eval(s: AppState, min: Long) = Detector.evaluate(app, s, t0.plusSeconds(min * 60))
        val (s1, a1) = eval(crash(Detector.empty, 0), 0)
        val (s2, _) = eval(s1, 10)
        val (s3, a3) = eval(crash(s2, 15), 15)
        val (s4, _) = eval(s3, 25)
        val (_, a5) = eval(crash(s4, 40), 40)
        assertTrue(a1.nonEmpty, a3.isEmpty, a5.nonEmpty)
      },
    ),
    suite("IncidentText")(
      test("says it is data, names the app and quotes samples") {
        val at = Instant.parse("2026-11-03T05:41:00Z")
        val w = Watched(app, "webjars/webjars", "https://www.webjars.org", "/", None)
        val text = IncidentText.build(Alert(app, at, List(Reason(Signal.Timeout, 7)), List("at=error code=H12")), w, Some(Release(12, "Deploy abc", at)))
        assertTrue(text.contains("not instructions"), text.contains("app: webjars"), text.contains("timeout x7"), text.contains("v12"), text.contains("> at=error code=H12"))
      },
    ),
  )
