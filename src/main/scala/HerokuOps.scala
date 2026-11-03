import zio.*
import zio.config.typesafe.*
import zio.http.*

object HerokuOps extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.fromResourcePath())

  // The health check a GitHub Actions cron job calls to watch the watcher (DESIGN.md in jamesward/projects)
  val routes: Routes[Any, Response] =
    Routes(
      Method.GET / "health" -> handler(Response.text("ok")),
    )

  def run = Server.serve(routes).provide(Server.configured())
