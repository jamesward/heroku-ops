package herokuops

import zio.schema.{Schema, derived}

import java.time.{Duration, Instant}

final case class UndeliveredOut(app: String, at: Instant, conditions: List[String], why: String) derives Schema

/** The fallback channel for an incident that couldn't be handed to its app's routine (no fire token, 429, a paused routine).
  *
  * Nothing here goes through a routine or needs a GitHub or Heroku credential. While such an incident is recent, heroku-ops
  * requests its own public `/alarm` once a second (`Ops.alarmTick`), and `/alarm` answers 503. The Heroku router counts those as
  * failed requests of this app, so the owner's threshold alert on the app ("failed requests" over a few percent, in the Heroku
  * dashboard under Metrics) fires and emails them. The alert is configured by hand; see AGENTS.md. */
object Alarm:
  /** How long after an undelivered incident the alarm keeps failing. Long enough for the alert's sensitivity window to see it. */
  val AlarmFor: Duration = Duration.ofMinutes(30)

  /** How long `/health` lists an undelivered incident. */
  val Listed: Duration = Duration.ofHours(24)

  def undelivered(s: OpsState, now: Instant, within: Duration): List[UndeliveredOut] =
    s.incidents.toList.reverse.collect:
      case IncidentRecord(at, app, reasons, _, Delivery.Undelivered(why), _) if at.isAfter(now.minus(within)) =>
        UndeliveredOut(app.value, at, reasons.map(r => s"${r.signal.toString.toLowerCase} x${r.count}"), why)

  /** This app's own address as an incoming request names it: the Heroku router sends `Host` (only a host the app is mapped to
    * reaches the app) and `X-Forwarded-Proto`. */
  def ownUrl(host: Option[String], proto: Option[String]): Option[String] =
    host.filter(h => h.nonEmpty && h.length <= 255 && h.forall(c => c.isLetterOrDigit || c == '.' || c == '-' || c == ':')).map: h =>
      s"${proto.filter(p => p == "http" || p == "https").getOrElse("http")}://$h"

  def active(s: OpsState, now: Instant): List[UndeliveredOut] = undelivered(s, now, AlarmFor)
