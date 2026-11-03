package herokuops

import zio.*
import zio.http.*
import zio.schema.{Schema, derived}
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.lang.management.ManagementFactory
import java.time.Instant

final case class UndeliveredOut(app: String, at: Instant, conditions: List[String], why: String) derives Schema

final case class Health(
    status: String,
    startedAt: Instant,
    configLoadedAt: Option[Instant],
    watchedApps: Int,
    drainBatches: Long,
    logLines: Long,
    rejected: Long,
    hooks: Long,
    fires: Long,
    fireFailures: Long,
    heapUsedMb: Long,
    heapMaxMb: Long,
    undelivered: List[UndeliveredOut],
) derives Schema

object OpsRoutes:
  private def response(o: Outcome): Response = o match
    case Outcome.Accepted     => Response.status(Status.NoContent)
    case Outcome.UnknownApp   => Response.status(Status.NotFound)
    case Outcome.Unauthorized => Response.status(Status.Unauthorized)

  /** One line per drain request, for the drain failure experiment: what Logplex sent (its frame id and message count identify a
    * batch, so a redelivery shows up as the same frame id twice) and what we answered. */
  def drainRequestLine(req: Request, bytes: Int, outcome: Outcome, status: Status): String =
    def h(name: String) = req.rawHeader(name).getOrElse("-")
    s"drain app=${req.queryParam("app").getOrElse("-")} frame=${h("Logplex-Frame-Id")} msgs=${h("Logplex-Msg-Count")} bytes=$bytes " +
      s"outcome=$outcome status=${status.code} ua=${h("User-Agent").take(30)}"

  /** Incidents that could not be handed to the app's routine in the last day. While there are any, `/health` answers 503: the
    * GitHub Actions job `watch.yml` polls it and opens an issue, which GitHub emails to the owner. It is the one channel that doesn't
    * go through a routine. */
  val UndeliveredWindow: java.time.Duration = java.time.Duration.ofHours(24)

  def undelivered(s: OpsState, now: Instant): List[UndeliveredOut] =
    s.incidents.toList.reverse.collect:
      case IncidentRecord(at, app, reasons, _, Delivery.Undelivered(why)) if at.isAfter(now.minus(UndeliveredWindow)) =>
        UndeliveredOut(app.value, at, reasons.map(r => s"${r.signal.toString.toLowerCase} x${r.count}"), why)

  private val health: ZIO[Ops, Nothing, Response] =
    for
      ops <- ZIO.service[Ops]
      s <- ops.state.get
      now <- Clock.instant
      rt = java.lang.Runtime.getRuntime
      mb = 1024L * 1024
      late = undelivered(s, now).take(10)
    yield Response(
      status = if late.isEmpty then Status.Ok else Status.ServiceUnavailable,
      body = Body.from(Health(
        if late.isEmpty then "ok" else "degraded",
        Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean.getStartTime), s.configLoadedAt, s.watched.size,
        s.stats.drainBatches, s.stats.logLines, s.stats.rejected, s.stats.hooks, s.stats.fires, s.stats.fireFailures,
        (rt.totalMemory - rt.freeMemory) / mb, rt.maxMemory / mb, late,
      )),
    )

  val routes: Routes[Ops, Response] = Routes(
    Method.GET / "health" -> handler(health),
    Method.POST / "drain" -> handler: (req: Request) =>
      for
        ops <- ZIO.service[Ops]
        body <- req.body.asArray.orElseFail(Response.badRequest)
        outcome <- ops.drain(req.queryParam("app"), req.rawHeader("Authorization"), body)
        res = outcome match
          case Outcome.Accepted => Response.status(ops.settings.drainStatus)
          case other            => response(other)
        _ <- ZIO.logInfo(drainRequestLine(req, body.length, outcome, res.status))
      yield res,
    Method.POST / "hook" -> handler: (req: Request) =>
      for
        ops <- ZIO.service[Ops]
        body <- req.body.asArray.orElseFail(Response.badRequest)
        outcome <- ops.hook(req.queryParam("app"), req.rawHeader("Heroku-Webhook-Hmac-SHA256"), body)
      yield response(outcome),
  )
