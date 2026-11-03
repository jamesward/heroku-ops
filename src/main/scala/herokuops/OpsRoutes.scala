package herokuops

import zio.*
import zio.http.*
import zio.schema.{Schema, derived}
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

import java.lang.management.ManagementFactory
import java.time.Instant

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

  private val health: ZIO[Ops, Nothing, Response] =
    for
      ops <- ZIO.service[Ops]
      s <- ops.state.get
      now <- Clock.instant
      rt = java.lang.Runtime.getRuntime
      mb = 1024L * 1024
      late = Alarm.undelivered(s, now, Alarm.Listed).take(10)
    yield Response(body = Body.from(Health(
      if late.isEmpty then "ok" else "degraded",
      Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean.getStartTime), s.configLoadedAt, s.watched.size,
      s.stats.drainBatches, s.stats.logLines, s.stats.rejected, s.stats.hooks, s.stats.fires, s.stats.fireFailures,
      (rt.totalMemory - rt.freeMemory) / mb, rt.maxMemory / mb, late,
    )))

  /** 503 while the alarm is on (see `Alarm`), 200 otherwise. Nothing else requests it but heroku-ops itself. */
  private val alarm: ZIO[Ops, Nothing, Response] =
    for
      ops <- ZIO.service[Ops]
      s <- ops.state.get
      now <- Clock.instant
      late = Alarm.active(s, now).take(10)
    yield Response(status = if late.isEmpty then Status.Ok else Status.ServiceUnavailable, body = Body.from(late))

  private val tables: Routes[Ops, Response] = Routes(
    Method.GET / "health" -> handler(health),
    Method.GET / "alarm" -> handler(alarm),
    Method.POST / "drain" -> handler: (req: Request) =>
      for
        ops <- ZIO.service[Ops]
        body <- req.body.asArray.orElseFail(Response.badRequest)
        outcome <- ops.drain(req.queryParam("app"), req.rawHeader("Authorization"), body)
      yield response(outcome),
    Method.POST / "hook" -> handler: (req: Request) =>
      for
        ops <- ZIO.service[Ops]
        body <- req.body.asArray.orElseFail(Response.badRequest)
        outcome <- ops.hook(req.queryParam("app"), req.rawHeader("Heroku-Webhook-Hmac-SHA256"), body)
      yield response(outcome),
  )

  /** Remembers the address of the first request, which is how `Ops.alarmTick` learns where to request `/alarm`. */
  private val learnOwnUrl: HandlerAspect[Ops, Unit] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request]: req =>
      ZIO.serviceWithZIO[Ops]: ops =>
        ops.ownUrl.update(_.orElse(Alarm.ownUrl(req.rawHeader("Host"), req.rawHeader("X-Forwarded-Proto")))).as((req, ())))

  val routes: Routes[Ops, Response] = tables @@ learnOwnUrl
