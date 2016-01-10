package ph.samson.sniffl.metadata

import java.nio.file.Paths
import java.util.{Collections, Date}

import com.flickr4java.flickr.people.User
import com.flickr4java.flickr.photos.{Photo, Size}
import com.typesafe.scalalogging.StrictLogging

import scala.language.implicitConversions

@SerialVersionUID(-4183038073470494971L)
case class SPhoto(
    userId: String,
    id: String,
    title: String,
    format: Option[String],
    media: String,
    dateAdded: Date,
    datePosted: Date,
    dateTaken: Date,
    lastUpdate: Date,
    originalSizeSource: Option[String],
    originalSecret: String,
    farm: String,
    server: String,
    file: Option[String] = None
) {
  def originalUrl = {
    media match {
      case "photo" => this.getOriginalUrl
      case "video" =>
        s"https://www.flickr.com/photos/$userId/$id/play/orig/$originalSecret/"
    }
  }

  def pageUrl = s"https://www.flickr.com/photos/$userId/$id"

  def path = file.map(Paths.get(_))
}

object SPhoto extends StrictLogging {
  def apply(user: User, fPhoto: Photo): SPhoto = {
    new SPhoto(
      user.getId,
      fPhoto.getId,
      fPhoto.getTitle,
      Option(fPhoto.getOriginalFormat),
      fPhoto.getMedia,
      fPhoto.getDateAdded,
      fPhoto.getDatePosted,
      fPhoto.getDateTaken,
      fPhoto.getLastUpdate,
      if (fPhoto.getOriginalSize != null) {
        Some(fPhoto.getOriginalSize.getSource)
      } else {
        None
      },
      fPhoto.getOriginalSecret,
      fPhoto.getFarm,
      fPhoto.getServer
    )
  }

  implicit def toFlickr(photo: SPhoto): Photo = {
    val p = new Photo()
    p.setId(photo.id)
    p.setTitle(photo.title)

    photo.format foreach { f =>
      p.setOriginalFormat(f)
    }

    p.setDateAdded(photo.dateAdded)
    p.setDatePosted(photo.datePosted)
    p.setDateTaken(photo.dateTaken)
    p.setLastUpdate(photo.lastUpdate)

    photo.originalSizeSource.foreach { source =>
      val size = new Size
      size.setSource(source)
      size.setLabel(Size.ORIGINAL)
      p.setSizes(Collections.singleton(size))
    }

    p.setOriginalSecret(photo.originalSecret)
    p.setFarm(photo.farm)
    p.setServer(photo.server)

    p
  }
}
