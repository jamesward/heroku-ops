package herokuops

import java.time.{Duration, Instant}

/** One of the app's own log lines (not the router's), from a dyno, quoted like a sample. `requestId` is the router's `X-Request-ID` when
  * the app logs it (`request_id`, the Skill's "Production Servers" convention). */
final case class AppLine(at: Instant, dyno: String, requestId: Option[String], line: String) derives CanEqual

/** Which request a router line is about: its `dyno=` and `request_id=` fields. */
final case class RequestRef(dyno: Option[String], requestId: Option[String]) derives CanEqual

/** A timeout or 5xx router line, which the app's lines are matched against: by request id, or by dyno and time. */
final case class Anchor(at: Instant, request: RequestRef):
  def dyno: Option[String] = request.dyno
  def requestId: Option[String] = request.requestId

/** The app's own lines around its timeouts and 5xx (#24): a router line says a request took 30 s, the app's lines say where the time went.
  * `recent` is the last [[AppLines.Around]] of lines, so a new timeout can pick up the lines logged before it; `kept` are the lines that
  * matched a timeout or 5xx. Both are bounded. */
final case class AppLines(recent: Vector[AppLine], kept: Vector[AppLine]):
  /** Adds a batch of the app's lines. `anchors` are the app's timeouts and 5xx; only those within [[AppLines.Around]] of `at` matter. */
  def add(lines: List[AppLine], anchors: List[Anchor], at: Instant): AppLines =
    val near = anchors.filter(a => !a.at.isBefore(at.minus(AppLines.Around)))
    val candidates = recent ++ lines
    val matched = if near.isEmpty then Vector.empty else candidates.filter(l => near.exists(AppLines.matches(_, l)))
    AppLines(
      recent = candidates.filter(l => !l.at.isBefore(at.minus(AppLines.Around))).takeRight(AppLines.RecentLines),
      kept = if matched.isEmpty then kept else (kept ++ matched).distinct.takeRight(AppLines.KeptLines),
    )

  /** The kept lines of one timeout or 5xx: those of the same request first, then the dyno's lines nearest to it in time. */
  def around(anchor: Anchor, limit: Int): List[AppLine] =
    val (same, other) = kept.filter(AppLines.matches(anchor, _)).partition(AppLines.sameRequest(anchor, _))
    (same.sortBy(_.at) ++ other.sortBy(l => Duration.between(anchor.at, l.at).abs)).take(limit).sortBy(_.at).toList

object AppLines:
  val empty: AppLines = AppLines(Vector.empty, Vector.empty)
  /** A request that times out took 30 s, and the app may log its end after the router gave up on it. */
  val Around: Duration = Duration.ofSeconds(30)
  /** javadocs logs about 150 lines a minute, so this is more than [[Around]] of it. */
  val RecentLines = 400
  val KeptLines = 500

  def sameRequest(a: Anchor, l: AppLine): Boolean = a.requestId.isDefined && a.requestId == l.requestId

  def matches(a: Anchor, l: AppLine): Boolean =
    sameRequest(a, l) ||
      (a.dyno.contains(l.dyno) && !l.at.isBefore(a.at.minus(Around)) && !l.at.isAfter(a.at.plus(Around)))
