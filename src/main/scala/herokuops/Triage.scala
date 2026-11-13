package herokuops

import zio.schema.{Schema, derived}

import java.time.Instant

final case class ProbeOut(at: Instant, ok: Boolean, status: Option[Int], millis: Long) derives Schema
final case class ReleaseOut(version: Int, at: Instant, description: String) derives Schema
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
) derives Schema
final case class MinutePoint(minute: Instant, requests: Int, signals: Map[String, Int]) derives Schema
final case class AppDetail(summary: AppSummary, lastMinutes: List[MinutePoint]) derives Schema
final case class Apps(apps: List[AppSummary]) derives Schema
final case class Series(app: String, points: List[MinutePoint]) derives Schema
final case class EventOut(at: Instant, signal: String, sample: String) derives Schema
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
final case class Releases(app: String, releases: List[ReleaseOut]) derives Schema

/** Read-only views of what heroku-ops knows, for the MCP tools. Pure: state and the time in, data out. */
object Triage:
  def find(s: OpsState, app: String): Either[String, Watched] =
    AppName.parse(app).toOption.flatMap(a => s.watched.get(a)).toRight:
      s"unknown app '$app'; watched apps: ${s.watched.keys.map(_.value).toList.sorted.mkString(", ")}"

  private def describe(r: Reason) = s"${r.signal.toString.toLowerCase} x${r.count}"

  private def release(r: Release) = ReleaseOut(r.version, r.at, r.description)

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
    )

  def apps(s: OpsState, now: Instant): Apps = Apps(s.watched.values.toList.sortBy(_.app.value).map(summary(s, _, now)))

  def series(s: OpsState, w: Watched, now: Instant, minutes: Int): List[MinutePoint] =
    val st = s.app(w.app)
    val current = Detector.minuteOf(now)
    ((current - minutes + 1) to current).toList.map: m =>
      val b = st.buckets.get(m)
      MinutePoint(Instant.ofEpochSecond(m * 60), b.map(_.requests).getOrElse(0),
        b.map(_.counts.collect { case (sig, n) if n > 0 => sig.toString.toLowerCase -> n }).getOrElse(Map.empty))

  def detail(s: OpsState, w: Watched, now: Instant): AppDetail = AppDetail(summary(s, w, now), series(s, w, now, 15))

  def events(s: OpsState, w: Watched, signal: Option[String], limit: Int): Events =
    val all = s.recent.getOrElse(w.app, Vector.empty).reverse
    val picked = signal.fold(all)(sig => all.filter(_.signal.toString.equalsIgnoreCase(sig)))
    Events(w.app.value, picked.take(limit).toList.map(n => EventOut(n.at, n.signal.toString.toLowerCase, n.sample)))

  def incidents(s: OpsState, app: Option[String], limit: Int): Incidents =
    val all = s.incidents.reverse.filter(i => app.forall(_.equalsIgnoreCase(i.app.value)))
    Incidents(all.take(limit).toList.map: i =>
      IncidentOut(i.id, i.at, i.app.value, i.reasons.map(describe), i.samples, i.delivery match
        case Delivery.Fired(session)    => s"fired the routine: $session"
        case Delivery.Undelivered(why)  => s"not delivered: $why"
      , i.added.map((at, rs) => AddedOut(at, rs.map(describe)))))

  def releases(s: OpsState, w: Watched): Releases = Releases(w.app.value, s.releases.getOrElse(w.app, Nil).map(release))
