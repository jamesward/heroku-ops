import zio.*
import zio.http.*
import zio.test.*

object HerokuOpsSpec extends ZIOSpecDefault:

  def spec = suite("HerokuOps")(
    test("GET /health answers ok") {
      for
        response <- HerokuOps.routes.runZIO(Request.get(URL(Path.root / "health")))
        body     <- response.body.asString
      yield assertTrue(response.status.code == 200, body == "ok")
    },
  )
