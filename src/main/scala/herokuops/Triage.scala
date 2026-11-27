package herokuops

import zio.schema.{Schema, derived}

import java.time.Instant

final case class ProbeOut(at: Instant, ok: Boolean, status: Option[Int], millis: Long) derives Schema
final case class ReleaseOut(version: Int, at: Instant, description: String) derives Schema
/** `rssSwapMb` is what Heroku's R14 compares with `quotaMb`; `totalMb` also counts reclaimable page cache (#25). */
final case class MemoryOut(rssSwapMb: Double, quotaMb: Double, rssMb: Double, swapMb: Double, cacheMb: Double, totalMb: Double) derives Schema, CanEqual
final case class MemoryReadingOut(at: Instant, dyno: String, memory: MemoryOut) derives Schema, CanEqual
final case class AppSummary(
    app: String,
    repo: String,
    url: String,
    status: String,
    logsSecondsAgo: Option[Long],
    probe: Option[ProbeOut],
    conditions: List[String],
    lastRelease: Option[ReleaseOut],
    routineConfigured: Boolean,
    memory: Option[MemoryReadingOut],
) derives Schema
/** How many clients (the router's `fwd`) sent the minute's requests, and how many the busiest one sent. `capped` when there were more than
  * `distinct` (#28). Addresses are never kept or returned, only counts. */
final case class ClientsOut(distinct: Int, busiest: Int, capped: Boolean) derives Schema, CanEqual
/** `memory` is the minute's peak of each field, across samples and dynos, when the app logs `log-runtime-metrics`. `signals` also has
  * `fast503`, the 503s answered within 5 ms (shedding, not failing). */
final case class MinutePoint(minute: Instant, requests: Int, signals: Map[String, Int], memory: Option[MemoryOut], clients: Option[ClientsOut] = None) derives Schema
final case class AppDetail(summary: AppSummary, lastMinutes: List[MinutePoint]) derives Schema
final case class Apps(apps: List[AppSummary]) derives Schema
final case class Series(app: String, points: List[MinutePoint]) derives Schema
/** One of the app's own lines near a timeout or 5xx; `sameRequest` when it carries the router line's request id (#24). */
final case class AppLineOut(at: Instant, dyno: String, sameRequest: Boolean, line: String) derives Schema, CanEqual
/** `appLines` only when asked for, and only for a timeout or 5xx. `buildOutput`: for the app's latest buildfailed, the end of its output. */
final case class EventOut(at: Instant, signal: String, sample: String, appLines: Option[List[AppLineOut]] = None, buildOutput: Option[List[String]] = None) derives Schema
final case class Events(app: String, events: List[EventOut]) derives Schema
final case class AddedOut(at: Instant, conditions: List[String]) derives Schema
final case class IncidentOut(
    id: String,
    at: Instant,
    app: String,
    conditions: List[String],
    samples: List[String],
    delivery: String,
    added: List[AddedOut],
) derives Schema
final case class Incidents(incidents: List[IncidentOut]) derives Schema
/** `historySince` is when heroku-ops started: releases are kept in memory, so an empty list means none seen since then. */
final case class Releases(app: String, releases: List[ReleaseOut], historySince: Instant) derives Schema

/** Read-only views of what heroku-ops knows, for the MCP tools. Pure: state and the time in, data out. */
object Triage:
  def find(s: OpsState, app: String): Either[String, Watched] =
    AppName.parse(app).toOption.flatMap(a => s.watched.get(a)).toRight:
      s"unknown app '$app'; watched apps: ${s.watched.keys.map(_.value).toList.sorted.mkString(", ")}"

  private def describe(r: Reason) = r.text

  private def release(r: Release) = ReleaseOut(r.version, r.at, r.description)

  private def memory(m: MemorySample, rssSwap: Double) = MemoryOut(rssSwap, m.quota, m.rss, m.swap, m.cache, m.total)

  def summary(s: OpsState, w: Watched, now: Instant): AppSummary =
    val st = s.app(w.app)
    val conditions = Detector.conditions(st, now)
    val status =
      if st.down then "down"
      else if conditions.nonEmpty then "degraded"
      else if s.lastLog.contains(w.app) || s.lastProbe.contains(w.app) then "ok"
      else "no-data"
    AppSummary(
      w.app.value, w.repo, w.url, status,
      s.lastLog.get(w.app).map(at => now.getEpochSecond - at.getEpochSecond),
      s.lastProbe.get(w.app).map(p => ProbeOut(p.at, p.ok, p.status, p.millis)),
      conditions.map(describe),
      s.releases.get(w.app).flatMap(_.headOption).map(release),
      w.routine.isDefined,
      st.lastMemory.map(m => MemoryReadingOut(m.at, m.dyno, memory(m.sample, m.sample.rssSwap))),
    )

  def apps(s: OpsState, now: Instant): Apps = Apps(s.watched.values.toList.sortBy(_.app.value).map(summary(s, _, now)))

  def series(s: OpsState, w: Watched, now: Instant, minutes: Int): List[MinutePoint] =
    val st = s.app(w.app)
    val current = Detector.minuteOf(now)
    ((current - minutes + 1) to current).toList.map: m =>
      val b = st.buckets.get(m)
      MinutePoint(Instant.ofEpochSecond(m * 60), b.map(_.requests).getOrElse(0),
        b.map(x => x.counts.collect { case (sig, n) if n > 0 => sig.toString.toLowerCase -> n } ++
          Option.when(x.fastUnavailable > 0)("fast503" -> x.fastUnavailable)).getOrElse(Map.empty),
        b.flatMap(_.memory).map(p => memory(p.fields, p.rssSwap)),
        b.flatMap(x => x.clients.values.maxOption.map(ClientsOut(x.clients.size, _, x.clients.size >= Detector.MaxClients))))

  def detail(s: OpsState, w: Watched, now: Instant): AppDetail = AppDetail(summary(s, w, now), series(s, w, now, 15))

  val AppLinesPerEvent = 20

  /** With `appLines`, a timeout or 5xx also has the app's own lines of the same request, or else of its dyno within [[AppLines.Around]]. */
  def events(s: OpsState, w: Watched, signal: Option[String], limit: Int, appLines: Boolean = false): Events =
    val all = s.recent.getOrElse(w.app, Vector.empty).reverse
    val picked = signal.fold(all)(sig => all.filter(_.signal.toString.equalsIgnoreCase(sig)))
    val lines = s.appLines.getOrElse(w.app, AppLines.empty)
    def context(n: Notable) = Option.when(appLines)(n.anchor).flatten.map: a =>
      lines.around(a, AppLinesPerEvent).map(l => AppLineOut(l.at, l.dyno, AppLines.sameRequest(a, l), l.line))
    def output(n: Notable) = s.builds.get(w.app).filter(b => n.signal == Signal.BuildFailed && b.at.equals(n.at) && b.lines.nonEmpty).map(_.lines)
    Events(w.app.value, picked.take(limit).toList.map(n => EventOut(n.at, n.signal.toString.toLowerCase, n.sample, context(n), output(n))))

  def incidents(s: OpsState, app: Option[String], limit: Int): Incidents =
    val all = s.incidents.reverse.filter(i => app.forall(_.equalsIgnoreCase(i.app.value)))
    Incidents(all.take(limit).toList.map: i =>
      IncidentOut(i.id, i.at, i.app.value, i.reasons.map(describe), i.samples, i.delivery match
        case Delivery.Fired(session)    => s"fired the routine: $session"
        case Delivery.Undelivered(why)  => s"not delivered: $why"
      , i.added.map((at, rs) => AddedOut(at, rs.map(describe)))))

  def releases(s: OpsState, w: Watched): Releases = Releases(w.app.value, s.releases.getOrElse(w.app, Nil).map(release), s.since)
