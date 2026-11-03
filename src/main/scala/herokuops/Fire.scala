package herokuops

import zio.*
import zio.http.*
import zio.schema.{Schema, derived}
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

/** Starts an app's routine through its API trigger. A 200 only means a session started, not that the work got done. */
object Fire:
  final case class Body(text: String) derives Schema

  private val Version = "2026-10-21"

  def send(id: RoutineId, token: String, text: String): ZIO[Client, String, String] =
    for
      url <- ZIO.fromEither(URL.decode(s"https://api.anthropic.com/v1/claude_code/routines/${id.value}/fire")).mapError(_.getMessage)
      request = Request.post(url, zio.http.Body.from(Body(text)))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader("anthropic-version", Version)
      response <- Client.batched(request).mapError(_.toString)
      reply <- response.body.asString.mapError(_.toString)
      session <-
        if response.status.isSuccess then ZIO.succeed(reply.take(300))
        else ZIO.fail(s"HTTP ${response.status.code}: ${reply.take(300)}")
    yield session
