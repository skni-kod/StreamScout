package pl.sknikod.streamscout
package token

import akka.stream.alpakka.cassandra.scaladsl.CassandraSession

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

case class TwitchToken(clientId: String, clientSecret: String, accessToken: String, refreshToken: String, expiresAt: Instant)

class TwitchTokenDAO(session: CassandraSession)(implicit ec: ExecutionContext) {

  def saveToken(token: TwitchToken): Future[Unit] = {
    session.executeWrite(
      """UPDATE streamscout.twitch_tokens
        |SET client_secret = ?, access_token = ?, refresh_token = ?, expires_at = ?
        |WHERE client_id = ?""".stripMargin,
      token.clientSecret, token.accessToken, token.refreshToken, token.expiresAt, token.clientId
    ).map(_ => ())
  }

  def getAllTokens: Future[List[TwitchToken]] = {
    session.selectAll("SELECT * FROM streamscout.twitch_tokens").map { rows =>
      rows.map(row => TwitchToken(
        row.getString("client_id"),
        row.getString("client_secret"),
        row.getString("access_token"),
        row.getString("refresh_token"),
        row.getInstant("expires_at")
      )).toList
    }
  }
}