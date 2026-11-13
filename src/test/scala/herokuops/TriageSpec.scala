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
        points.last.memory == Some(MemoryOut(505.16, 500, 3, 8.18, 1024)),
        points(1).memory == Some(MemoryOut(505.16, 496.36, 0.62, 8.18, 1024)),
        points.head.memory.isEmpty,
        Triage.summary(s, w, now).memory == Some(MemoryReadingOut(now, "web.2", MemoryOut(480, 500, 3, 0, 1024))),
        Triage.summary(base, w, now).memory.isEmpty,
      )
    },
    test("releases say since when they were kept (#22)") {
      assertTrue(Triage.releases(base, w).releases.isEmpty, Triage.releases(base, w).historySince == base.since)
    },
    test("an unknown app lists the watched ones") {
      assertTrue(Triage.find(base, "nope") == Left("unknown app 'nope'; watched apps: webjars"))
    },
    test("recent events are bounded per app") {
      val s = (1 to 300).foldLeft(base)((st, i) => st.record(app, List(Event.Observed(Signal.AppError, s"e$i")), now))
      assertTrue(s.recent(app).size == OpsState.RecentPerApp, s.recent(app).last.sample == "e300")
    },
  )
