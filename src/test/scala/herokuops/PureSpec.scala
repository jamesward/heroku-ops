package herokuops

import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.chaining.*

object PureSpec extends ZIOSpecDefault:
  private given CanEqual[Instant, Instant] = CanEqual.derived

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
      test("a JSON line counts by its own level, not by ERROR or FATAL in its message or cause (#14)") {
        def n(t: String) = Classifier.classify(LogMessage("app", "web.1", t)).size
        val warnWithFatalCause =
          """{"timestamp":"2026-11-12T20:36:24.792651504Z","level":"WARN","message":"javadocs - Pool is empty, failed to create/setup connection","cause":"org.postgresql.util.PSQLException: FATAL: role \"u397\" is not permitted to log in"}"""
        val warnWithErrorTrace = """{"level":"WARN","message":"Index load failed","cause":"java.sql.SQLTransientConnectionException: ERROR at line 3"}"""
        assertTrue(n(warnWithFatalCause) == 0, n(warnWithErrorTrace) == 0, n("""{"level":"FATAL","message":"x"}""") == 1, n("FATAL: role is not permitted") == 1)
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
        // #16: a one-off `heroku run` dyno whose command exited 1, and a release phase, aren't the app crashing.
        val oneOff = parse("""{"resource":"dyno","action":"destroy","data":{"name":"run.9817","type":"run","state":"crashed","exit_status":1}}""")
        val releasePhase = parse("""{"resource":"dyno","action":"destroy","data":{"name":"release.1234","type":"release","state":"crashed"}}""")
        assertTrue(
          failed.collect { case HookResult.Observed(s, _) => s } == List(Signal.ReleaseFailed),
          ok.collect { case HookResult.Released(r) => r.version } == List(8),
          crash == List(HookResult.DynoCrashed),
          oneOff.isEmpty,
          releasePhase.isEmpty,
          Webhook.parse("not json".getBytes(UTF_8), at).isLeft,
        )
      },
    ),
    suite("OpsState")(
      test("a release's repeated webhooks are one entry per version, the latest (#15)") {
        val t = Instant.parse("2026-11-12T20:35:15Z")
        val s = OpsState.empty
          .released(app, Release(244, "Update DATABASE", t))
          .released(app, Release(244, "Update DATABASE", t.plusMillis(294)))
          .released(app, Release(245, "Update DATABASE", t.plusSeconds(228)))
          .released(app, Release(245, "Update DATABASE", t.plusSeconds(228).plusMillis(94)))
        assertTrue(s.releases(app).map(_.version) == List(245, 244), s.releases(app).head.at == t.plusSeconds(228).plusMillis(94))
      },
      test("conditions within 30 minutes of a delivered incident are added to it; an undelivered one doesn't hold the window (#15)") {
        val t0 = Instant.parse("2026-11-12T21:07:22Z")
        val fired = IncidentRecord(t0, app, List(Reason(Signal.Crash, 1)), List("State changed from up to crashed"), Delivery.Fired("s1"))
        val s = OpsState.empty.incident(fired)
        val memory = Alert(app, t0.plusSeconds(60), List(Reason(Signal.Memory, 4)), List("Error R14 (Memory quota exceeded)"))
        val folded = s.fold(s.openIncident(app, memory.at).get, memory)
        val undelivered = OpsState.empty.incident(fired.copy(delivery = Delivery.Undelivered("no token")))
        assertTrue(
          s.openIncident(app, t0.plusSeconds(60)).map(_.id) == Some(s"webjars-${t0.getEpochSecond / 60}"),
          s.openIncident(app, t0.plusSeconds(31 * 60)).isEmpty,
          folded.incidents.size == 1,
          folded.incidents.head.added == List(memory.at -> List(Reason(Signal.Memory, 4))),
          folded.incidents.head.samples == List("State changed from up to crashed", "Error R14 (Memory quota exceeded)"),
          undelivered.openIncident(app, t0.plusSeconds(60)).isEmpty,
        )
      },
    ),
    suite("Detector")(
      test("one crash's lines (R15, State changed, the dyno webhook) count as one crash, with all of them as samples") {
        val t0 = Instant.parse("2026-11-12T21:45:02.229Z")
        val s1 = Detector.record(Detector.empty, Event.Observed(Signal.Crash, "Error R15 (Memory quota vastly exceeded)"), t0)
        val s2 = Detector.record(s1, Event.Observed(Signal.Crash, "State changed from up to crashed"), t0.plusMillis(287))
        val s3 = Detector.dynoCrashed(s2, t0.plusMillis(731))
        val (s4, _) = Detector.settle(s3, t0.plusSeconds(40))
        val (_, alert) = Detector.evaluate(app, s4, t0.plusSeconds(40))
        assertTrue(alert.map(_.reasons) == Some(List(Reason(Signal.Crash, 1))), alert.exists(_.samples.size == 3))
      },
      test("two crashes a minute apart count as two") {
        val t0 = Instant.parse("2026-11-12T21:07:16Z")
        val s1 = Detector.record(Detector.empty, Event.Observed(Signal.Crash, "State changed from up to crashed"), t0)
        val s2 = Detector.record(s1, Event.Observed(Signal.Crash, "State changed from up to crashed"), t0.plusSeconds(60))
        assertTrue(Detector.conditions(s2, t0.plusSeconds(60)) == List(Reason(Signal.Crash, 2)))
      },
      test("a dyno webhook 'crashed' just before a release (javadocs v247) is the release's restart, not a crash (#17)") {
        val crashAt = Instant.parse("2026-11-13T04:50:41.99Z")
        val held = Detector.dynoCrashed(Detector.empty, crashAt)
        val (early, _) = Detector.evaluate(app, held, crashAt.plusSeconds(5)) // an evaluation before the release arrives
        val released = Detector.released(early, Instant.parse("2026-11-13T04:50:42.87Z"))
        val (settled, counted) = Detector.settle(released, crashAt.plusSeconds(60))
        val (_, alert) = Detector.evaluate(app, settled, crashAt.plusSeconds(60))
        assertTrue(Detector.evaluate(app, held, crashAt.plusSeconds(5))._2.isEmpty, counted.isEmpty, alert.isEmpty)
      },
      test("a dyno webhook 'crashed' just after a release is dropped at once") {
        val releaseAt = Instant.parse("2026-11-12T20:35:13Z")
        val s = Detector.dynoCrashed(Detector.released(Detector.empty, releaseAt), releaseAt.plusSeconds(2))
        assertTrue(s.heldDynoCrashes.isEmpty)
      },
      test("a dyno webhook 'crashed' with no release around it counts once it's 30 s old (heroku-ops has no drain to itself)") {
        val t0 = Instant.parse("2026-11-13T04:00:00Z")
        val held = Detector.dynoCrashed(Detector.empty, t0)
        val (notYet, none) = Detector.settle(held, t0.plusSeconds(20))
        val (settled, counted) = Detector.settle(notYet, t0.plusSeconds(31))
        val (_, alert) = Detector.evaluate(app, settled, t0.plusSeconds(31))
        assertTrue(none.isEmpty, counted == List(t0), alert.map(_.reasons) == Some(List(Reason(Signal.Crash, 1))))
      },
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
    suite("McpRequestLog")(
      test("describes an initialize, a tool call, a notification and junk without ever logging the token") {
        val init = McpRequestLog.line("POST", Some("Claude-User/1.0"), false, Some("2025-11-25"),
          """{"jsonrpc":"2.0","id":7,"method":"initialize","params":{}}""")
        val call = McpRequestLog.line("POST", None, true, None,
          """{"jsonrpc":"2.0","id":"a","method":"tools/call","params":{"name":"list_apps","arguments":{}}}""")
        val note = McpRequestLog.line("POST", None, true, None, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val junk = McpRequestLog.line("GET", Some("x\ny"), false, None, "")
        assertTrue(
          init.contains("rpc=initialize"), init.contains("auth=none"), init.contains("ua=Claude-User/1.0"), init.contains("protocol=2025-11-25"),
          call.contains("rpc=tools/call"), call.contains("tool=list_apps"), call.contains("auth=sent"),
          note.contains("rpc=notifications/initialized"), note.contains("id=-"),
          junk.contains("rpc=-"), junk.contains("ua=x y"),
        )
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
