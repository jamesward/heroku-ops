package herokuops

import java.time.{Duration, Instant}

final case class Notable(at: Instant, signal: Signal, sample: String)

enum Delivery derives CanEqual:
  case Fired(session: String)
  case Undelivered(why: String)

/** An alert told to the app's routine (or not). `added` are the later conditions folded into it (see [[OpsState.FoldWindow]]). */
final case class IncidentRecord(
    at: Instant,
    app: AppName,
    reasons: List[Reason],
    samples: List[String],
    delivery: Delivery,
    added: List[(Instant, List[Reason])] = Nil,
):
  def id: String = IncidentRecord.id(app, at)

object IncidentRecord:
  def id(app: AppName, at: Instant): String = s"${app.value}-${at.getEpochSecond / 60}"

final case class ProbeResult(at: Instant, ok: Boolean, status: Option[Int], millis: Long)

final case class Stats(drainBatches: Long, logLines: Long, rejected: Long, hooks: Long, fires: Long, fireFailures: Long)

object Stats:
  val empty: Stats = Stats(0, 0, 0, 0, 0, 0)

/** Everything heroku-ops remembers, in memory. Bounded: per app, 60 minute buckets and a few quoted samples. */
final case class OpsState(
    watched: Map[AppName, Watched],
    configLoadedAt: Option[Instant],
    apps: Map[AppName, AppState],
    releases: Map[AppName, List[Release]],
    recent: Map[AppName, Vector[Notable]],
    incidents: Vector[IncidentRecord],
    lastProbe: Map[AppName, ProbeResult],
    lastLog: Map[AppName, Instant],
    fireTimes: List[Instant],
    stats: Stats,
):
  def app(name: AppName): AppState = apps.getOrElse(name, Detector.empty)

  def record(name: AppName, events: List[Event], at: Instant): OpsState =
    val notable = events.collect { case Event.Observed(sig, sample) => Notable(at, sig, sample) }
    copy(
      apps = apps.updated(name, events.foldLeft(app(name))(Detector.record(_, _, at))),
      recent = recent.updated(name, (recent.getOrElse(name, Vector.empty) ++ notable).takeRight(OpsState.RecentPerApp)),
    )

  /** Heroku sends a release's webhook more than once (create and update, ~100–300 ms apart): one entry per version, the latest. */
  def released(name: AppName, release: Release): OpsState =
    val others = releases.getOrElse(name, Nil).filterNot(_.version == release.version)
    copy(
      releases = releases.updated(name, (release :: others).take(OpsState.ReleasesPerApp)),
      apps = apps.updated(name, Detector.released(app(name), release.at)),
    )

  def dynoCrashed(name: AppName, at: Instant): OpsState = copy(apps = apps.updated(name, Detector.dynoCrashed(app(name), at)))

  /** Counts held dyno crashes that no release explained (see [[Detector.settle]]), and lists them in the recent events. */
  def settle(at: Instant): OpsState =
    apps.foldLeft(this): (s, entry) =>
      val (name, st) = entry
      val (settled, times) = Detector.settle(st, at)
      val notable = times.map(Notable(_, Signal.Crash, Detector.DynoCrashedSample))
      s.copy(
        apps = s.apps.updated(name, settled),
        recent = if notable.isEmpty then s.recent
                 else s.recent.updated(name, (s.recent.getOrElse(name, Vector.empty) ++ notable).takeRight(OpsState.RecentPerApp)),
      )

  /** The app's incident that was handed to its routine less than [[OpsState.FoldWindow]] ago: a new condition then is part of it. */
  def openIncident(name: AppName, at: Instant): Option[IncidentRecord] =
    incidents.reverseIterator.find(_.app == name).filter: i =>
      i.at.isAfter(at.minus(OpsState.FoldWindow)) && (i.delivery match
        case Delivery.Fired(_)       => true
        case Delivery.Undelivered(_) => false)

  /** Adds a later alert's conditions and samples to the open incident instead of starting another routine run. */
  def fold(open: IncidentRecord, alert: Alert): OpsState =
    val merged = open.copy(
      samples = (open.samples ++ alert.samples).distinct.take(OpsState.SamplesPerIncident),
      added = open.added :+ (alert.at -> alert.reasons),
    )
    copy(incidents = incidents.map(i => if i.id == open.id && i.at.equals(open.at) then merged else i))

  def incident(record: IncidentRecord): OpsState = copy(incidents = (incidents :+ record).takeRight(OpsState.IncidentsKept))

  def probed(name: AppName, ok: Boolean): OpsState = copy(apps = apps.updated(name, Detector.probe(app(name), ok)))

  def bump(f: Stats => Stats): OpsState = copy(stats = f(stats))

  /** The global ceiling on fires per hour, for a platform-wide storm. */
  def mayFire(at: Instant): Boolean = fireTimes.count(_.isAfter(at.minus(Duration.ofHours(1)))) < OpsState.MaxFiresPerHour

object OpsState:
  val MaxFiresPerHour = 10
  val RecentPerApp = 200
  val ReleasesPerApp = 10
  val IncidentsKept = 100
  val SamplesPerIncident = 10
  /** While an app's routine was told about an incident this recently, new conditions are added to that incident rather than starting a
    * second run: one javadocs outage started runs for `crash`, then `memory` a minute later and `apperror` four minutes later, all from
    * the same event and all writing to the same issue. Undelivered incidents don't hold this window, so a later alert can still deliver. */
  val FoldWindow: Duration = Duration.ofMinutes(30)

  val empty: OpsState = OpsState(Map.empty, None, Map.empty, Map.empty, Map.empty, Vector.empty, Map.empty, Map.empty, Nil, Stats.empty)
