// Copyright: 2017 - 2018 Sam Halliday, 2020 Zara Turtle
// License: https://firstdonoharm.dev/version/2/1/license.html

package fpmortals
package http

import cats._, implicits._
import cats.mtl._, cats.mtl.implicits._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Url
import jsonformat._
import http.encoding._
import http.oauth2._
import time._

/**
 * A JSON HTTP client that transparently uses OAUTH 2.0 under the hood for
 * authentication. Methods are the same as on JsonClient but are not inherited,
 * to emphasise the different semantics.
 */
trait OAuth2JsonClient[F[_]] {

  def get[A: JsDecoder](
    uri: String Refined Url,
    headers: List[(String, String)]
  ): F[A]

  def post[P: UrlEncodedWriter, A: JsDecoder](
    uri: String Refined Url,
    payload: P,
    headers: List[(String, String)]
  ): F[A]

}
object OAuth2JsonClient {
  private[http] def mkHeader(b: BearerToken): (String, String) =
    "Authorization" -> ("Bearer " + b.token)
}

import OAuth2JsonClient.mkHeader

final class OAuth2JsonClientModule[F[_]: Monad](
  token: RefreshToken
)(
  H: JsonClient[F],
  T: LocalClock[F],
  A: Refresh[F]
)(
  implicit F: MonadState[F, BearerToken]
) extends OAuth2JsonClient[F] {

  // if we wanted to add more resilience and re-obtain a token if the H.get
  // fails, we could do so here, but we would need to request a MonadError to be
  // able to detect failures: which means we need to remove the `implicit`
  // keyword to avoid ambiguity.

  def get[A: JsDecoder](
    uri: String Refined Url,
    headers: List[(String, String)]
  ): F[A] =
    for {
      bearer <- goodBearer
      res    <- H.get(uri, mkHeader(bearer) :: headers)
    } yield res

  def post[P: UrlEncodedWriter, A: JsDecoder](
    uri: String Refined Url,
    payload: P,
    headers: List[(String, String)]
  ): F[A] =
    for {
      bearer <- goodBearer
      res    <- H.post(uri, payload, mkHeader(bearer) :: headers)
    } yield res

  private def goodBearer: F[BearerToken] =
    for {
      now    <- T.now
      stored <- F.get
      valid <- {
        if (stored.expires < now) A.bearer(token) flatTap F.set
        else stored.pure[F]
      }
    } yield valid

}

/**
 * For simple servers that don't implement OAuth2 refresh.
 */
final class BearerJsonClientModule[F[_]: Monad](
  bearer: BearerToken
)(
  H: JsonClient[F]
) extends OAuth2JsonClient[F] {

  def get[A: JsDecoder](
    uri: String Refined Url,
    headers: List[(String, String)]
  ): F[A] = H.get(uri, mkHeader(bearer) :: headers)

  def post[P: UrlEncodedWriter, A: JsDecoder](
    uri: String Refined Url,
    payload: P,
    headers: List[(String, String)]
  ): F[A] = H.post(uri, payload, mkHeader(bearer) :: headers)

}
