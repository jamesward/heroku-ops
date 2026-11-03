package herokuops

import zio.*
import zio.http.*

object HerokuOps extends ZIOAppDefault:
  /** Drain batches are small, but a busy app can send a few hundred KB at once. */
  private val MaxRequestBytes = 2 * 1024 * 1024

  private val serverConfig: ZLayer[Any, Nothing, Server.Config] = ZLayer.fromZIO:
    System.envOrElse("PORT", "8080").orDie.flatMap: port =>
      ZIO.fromOption(port.toIntOption).orDieWith(_ => IllegalStateException(s"PORT is not a number: $port"))
    .map(Server.Config.default.port(_).disableRequestStreaming(MaxRequestBytes))

  private val background: ZIO[Ops & Scope, Nothing, Unit] =
    for
      ops <- ZIO.service[Ops]
      _ <- ops.refreshConfig
      _ <- ops.refreshConfig.schedule(Schedule.fixed(5.minutes)).forkScoped
      _ <- ops.probeAll.schedule(Schedule.fixed(1.minute)).forkScoped
      _ <- ops.evaluateAll.schedule(Schedule.fixed(30.seconds)).forkScoped
      alarmOn <- Ref.make(false)
      _ <- ops.alarmTick(alarmOn).schedule(Schedule.fixed(1.second)).forkScoped
    yield ()

  /** JSON logs (one event per line, `request_id` from the router's `X-Request-ID` on every entry made while a request is handled).
    * Installed as the bootstrap, not with `.provide`, so the fibers the server forks inherit it. */
  override val bootstrap = AppLogging.json

  /** Every route, with the request id on its log entries. */
  val app: Routes[Ops & Settings, Response] = (OpsRoutes.routes ++ OpsMcp.routes) @@ OpsRoutes.requestId

  def run =
    (background *> Server.serve(app))
      .provide(Ops.live, Settings.live, Client.default, Server.live, serverConfig, Scope.default)
