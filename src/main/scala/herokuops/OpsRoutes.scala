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
      rt = java.lang.Runtime.getRuntime
      mb = 1024L * 1024
    yield Response(body = Body.from(Health(
      "ok", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean.getStartTime), s.configLoadedAt, s.watched.size,
      s.stats.drainBatches, s.stats.logLines, s.stats.rejected, s.stats.hooks, s.stats.fires, s.stats.fireFailures,
      (rt.totalMemory - rt.freeMemory) / mb, rt.maxMemory / mb,
    )))

  val routes: Routes[Ops, Response] = Routes(
    Method.GET / "health" -> handler(health),
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
