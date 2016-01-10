package ph.samson.sniffl

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

import com.flickr4java.flickr.auth.{Auth, Permission}
import com.flickr4java.flickr.photos.PhotosInterface
import com.flickr4java.flickr.photosets.PhotosetsInterface
import com.flickr4java.flickr.{REST, RequestContext, Flickr => Flickr4J}
import org.scribe.model.Verifier

import scala.io.StdIn
import scala.util.control.NonFatal

object Flickr {

  val apiKey = "128ee0615e58b74a4c755e2818fcb68a"
  val sharedSecret = "c1f8993896a9b637"

  def flickr(): Flickr4J = {
    val transport = new REST()
    transport.setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt)
    transport.setReadTimeoutMs(TimeUnit.MINUTES.toMillis(1).toInt)
    new Flickr4J(apiKey, sharedSecret, transport)
  }

  def flickr(auth: Auth): Flickr4J = {
    val f = flickr()
    f.setAuth(auth)
    f
  }

  def requestAuth(): Auth = {
    val authIf = flickr().getAuthInterface
    val requestToken = authIf.getRequestToken
    val authUrl = authIf.getAuthorizationUrl(requestToken, Permission.READ)

    try {
      Desktop.getDesktop.browse(URI.create(authUrl))
    } catch {
      case NonFatal(ex) => println(s"Open your web browser to $authUrl")
    }
    val code = StdIn.readLine("Enter Flickr auth code: ")
    val accessToken = authIf.getAccessToken(requestToken, new Verifier(code))

    authIf.checkToken(accessToken)
  }

  def photos(implicit auth: Auth): PhotosInterface = {
    RequestContext.getRequestContext.setAuth(auth)
    flickr(auth).getPhotosInterface
  }

  def photosets(implicit auth: Auth): PhotosetsInterface = {
    RequestContext.getRequestContext.setAuth(auth)
    flickr(auth).getPhotosetsInterface
  }
}
