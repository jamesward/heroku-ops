package herokuops

import java.time.{Duration, Instant}

final case class Stats(drainBatches: Long, logLines: Long, rejected: Long, hooks: Long, fires: Long, fireFailures: Long)

object Stats:
  val empty: Stats = Stats(0, 0, 0, 0, 0, 0)

/** Everything heroku-ops remembers, in memory. Bounded: per app, 60 minute buckets and a few quoted samples. */
final case class OpsState(
    watched: Map[AppName, Watched],
    configLoadedAt: Option[Instant],
    apps: Map[AppName, AppState],
    releases: Map[AppName, Release],
    lastLog: Map[AppName, Instant],
    fireTimes: List[Instant],
    stats: Stats,
):
  def app(name: AppName): AppState = apps.getOrElse(name, Detector.empty)

  def record(name: AppName, events: List[Event], at: Instant): OpsState =
    copy(apps = apps.updated(name, events.foldLeft(app(name))(Detector.record(_, _, at))))

  def probed(name: AppName, ok: Boolean): OpsState = copy(apps = apps.updated(name, Detector.probe(app(name), ok)))

  def bump(f: Stats => Stats): OpsState = copy(stats = f(stats))

  /** The global ceiling on fires per hour, for a platform-wide storm. */
  def mayFire(at: Instant): Boolean = fireTimes.count(_.isAfter(at.minus(Duration.ofHours(1)))) < OpsState.MaxFiresPerHour

object OpsState:
  val MaxFiresPerHour = 10

  val empty: OpsState = OpsState(Map.empty, None, Map.empty, Map.empty, Map.empty, Nil, Stats.empty)
