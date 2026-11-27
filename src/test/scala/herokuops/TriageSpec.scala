package herokuops

import zio.test.*

import java.time.Instant

object TriageSpec extends ZIOSpecDefault:
  private given CanEqual[Instant, Instant] = CanEqual.derived
  private val app = AppName.parse("webjars").toOption.get
  private val w = Watched(app, "webjars/webjars", "https://www.webjars.org", "/", None)
  private val now = Instant.parse("2026-11-03T05:41:30Z")
  private val base = OpsState.empty(Instant.parse("2026-11-03T04:00:00Z")).copy(watched = Map(app -> w))

  def spec = suite("Triage")(
    test("status: no-data, ok, degraded, down") {
      val ok = base.copy(lastLog = Map(app -> now))
      val degraded = ok.record(app, List(Event.Observed(Signal.Crash, "crashed")), now)
      val down = ok.copy(apps = Map(app -> (1 to 3).foldLeft(Detector.empty)((s, _) => Detector.probe(s, false))))
      assertTrue(
        Triage.summary(base, w, now).status == "no-data",
        Triage.summary(ok, w, now).status == "ok",
        Triage.summary(degraded, w, now).status == "degraded",
        Triage.summary(degraded, w, now).conditions == List("crash x1"),
        Triage.summary(down, w, now).status == "down",
      )
    },
    test("series has one point per minute, oldest first, with counts") {
      val s = base.record(app, List(Event.Request, Event.Request, Event.Observed(Signal.Timeout, "h12")), now)
      val points = Triage.series(s, w, now, 3)
      assertTrue(points.size == 3, points.last.requests == 2, points.last.signals == Map("timeout" -> 1), points.head.requests == 0, points.head.minute.isBefore(points.last.minute))
    },
    test("events are newest first, filterable and capped") {
      val s = base.record(app, List(Event.Observed(Signal.Crash, "a")), now).record(app, List(Event.Observed(Signal.Timeout, "b")), now.plusSeconds(5))
      val all = Triage.events(s, w, None, 10).events
      assertTrue(all.map(_.sample) == List("b", "a"), Triage.events(s, w, Some("CRASH"), 10).events.map(_.sample) == List("a"), Triage.events(s, w, None, 1).events.size == 1)
    },
    test("releases and incidents are kept newest first and bounded") {
      val rel = (1 to 15).foldLeft(base)((s, v) => s.released(app, Release(v, s"v$v", now)))
      val inc = (1 to 150).foldLeft(base)((s, i) => s.incident(IncidentRecord(now.plusSeconds(i), app, List(Reason(Signal.Crash, 1)), Nil, Delivery.Undelivered(s"n$i"))))
      assertTrue(
        Triage.releases(rel, w).releases.map(_.version) == (15 to 6 by -1).toList,
        inc.incidents.size == OpsState.IncidentsKept,
        Triage.incidents(inc, Some("webjars"), 2).incidents.head.delivery == "not delivered: n150",
      )
    },
    test("memory: per-minute peak across samples and dynos, and the latest reading (#22)") {
      val a = MemorySample(505.16, 496.36, 0.62, 8.18, 1024)
      val b = MemorySample(480, 500, 3, 0, 1024)
      val s = base.record(app, List(Event.Measured("web.1", a)), now.minusSeconds(60))
        .record(app, List(Event.Measured("web.1", a), Event.Measured("web.2", b)), now)
      val points = Triage.series(s, w, now, 3)
      assertTrue(
        // rss+swap is the larger of one sample's sum (504.54), not the sum of the separate peaks (508.18) (#25)
        points.last.memory == Some(MemoryOut(496.36 + 8.18, 1024, 500, 8.18, 3, 505.16)),
        points(1).memory == Some(MemoryOut(496.36 + 8.18, 1024, 496.36, 8.18, 0.62, 505.16)),
        points.head.memory.isEmpty,
        Triage.summary(s, w, now).memory == Some(MemoryReadingOut(now, "web.2", MemoryOut(500, 1024, 500, 0, 3, 480))),
        Triage.summary(base, w, now).memory.isEmpty,
      )
    },
    test("releases say since when they were kept (#22)") {
      assertTrue(Triage.releases(base, w).releases.isEmpty, Triage.releases(base, w).historySince == base.since)
    },
    test("series counts fast 503s and the minute's clients, never their addresses (#28)") {
      val lines = List("1.2.3.4", "1.2.3.4", "5.6.7.8").map(c => s"""at=info method=GET path="/" fwd="$c" dyno=web.1 service=1ms status=503""")
      val s = base.record(app, lines.flatMap(l => Classifier.classify(LogMessage("heroku", "router", l))), now)
      val p = Triage.series(s, w, now, 1).last
      assertTrue(p.signals == Map("servererror" -> 3, "fast503" -> 3), p.clients == Some(ClientsOut(2, 2, false)))
    },
    test("an unknown app lists the watched ones") {
      assertTrue(Triage.find(base, "nope") == Left("unknown app 'nope'; watched apps: webjars"))
    },
    test("a timeout's event can carry the app's own lines: its request's, then its dyno's within 30 s (#24)") {
      val t0 = now.minusSeconds(120)
      def batch(s: OpsState, at: Instant, msgs: LogMessage*) =
        s.record(app, msgs.toList.flatMap(Classifier.classify), at).logged(app, msgs.toList.flatMap(Classifier.appLine(_, at)), at)
      def line(dyno: String, text: String) = LogMessage("app", dyno, text)
      val h12 = LogMessage("heroku", "router", """at=error code=H12 desc="Request timeout" method=GET path="/a.jar?x=1" host=h request_id=rid-1 fwd="1.2.3.4" dyno=web.1 connect=0ms service=30000ms status=503""")
      val s = Seq(
        t0.minusSeconds(40) -> line("web.1", "too early"),
        t0 -> line("web.1", """{"level":"INFO","message":"Downloading jar","request_id":"rid-1"}"""),
        t0.plusSeconds(10) -> line("web.1", "other request on web.1"),
        t0.plusSeconds(10) -> line("web.2", "another dyno"),
        t0.plusSeconds(30) -> h12,
        t0.plusSeconds(35) -> line("web.1", """{"level":"INFO","message":"Downloaded jar","durationMs":41000,"request_id":"rid-1"}"""),
        t0.plusSeconds(70) -> line("web.1", "too late"),
      ).foldLeft(base)((st, e) => batch(st, e._1, e._2))
      val timeout = Triage.events(s, w, Some("timeout"), 10, appLines = true).events
      val lines = timeout.flatMap(_.appLines.toList.flatten)
      assertTrue(
        timeout.size == 1,
        lines.map(l => (l.line.take(30), l.sameRequest)) == List(
          ("""INFO "Downloading jar" request""", true), ("other request on web.1", false), ("""INFO "Downloaded jar" request_""", true)),
        lines.map(_.at) == List(t0, t0.plusSeconds(10), t0.plusSeconds(35)),
        lines.forall(_.dyno == "web.1"),
        Triage.events(s, w, Some("timeout"), 10).events.head.appLines.isEmpty,
      )
    },
    test("app lines are bounded per app (#24)") {
      val h12 = Event.Observed(Signal.Timeout, "h12", Some(RequestRef(Some("web.1"), None)))
      val lines = (1 to 1000).foldLeft(base): (st, i) =>
        val at = now.plusSeconds(i)
        st.record(app, List(h12), at).logged(app, List(AppLine(at, "web.1", None, s"l$i")), at)
      assertTrue(lines.appLines(app).kept.size == AppLines.KeptLines, lines.appLines(app).recent.size <= 31, lines.appLines(app).kept.last.line == "l1000")
    },
    test("recent events are bounded per app") {
      val s = (1 to 300).foldLeft(base)((st, i) => st.record(app, List(Event.Observed(Signal.AppError, s"e$i")), now))
      assertTrue(s.recent(app).size == OpsState.RecentPerApp, s.recent(app).last.sample == "e300")
    },
  )
