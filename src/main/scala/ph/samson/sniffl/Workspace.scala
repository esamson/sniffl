package ph.samson.sniffl

import java.io._
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption._
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.{Executors, TimeUnit}
import javax.mail.internet.ContentDisposition
import javax.net.ssl.HttpsURLConnection

import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.photos.{
  Extras,
  Photo,
  PhotoList,
  SearchParameters
}
import com.flickr4java.flickr.photosets.Photoset
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.StrictLogging
import ph.samson.sniffl.metadata.SPhoto

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import scala.util.control.NonFatal

class Workspace(workingDir: Path) extends StrictLogging {

  private implicit val requestPool = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(10,
                                 new ThreadFactoryBuilder()
                                   .setDaemon(true)
                                   .setNameFormat("request-%d")
                                   .build()))

  private val configDir = {
    val dir = workingDir.resolve(".sniffl")

    if (Files.notExists(dir)) {
      Files.createDirectories(dir)
    }

    val attributes = Files.readAttributes(dir, classOf[BasicFileAttributes])
    if (!attributes.isDirectory) {
      throw new IllegalStateException(s"$dir exists but is not a directory")
    }

    dir
  }

  def snifflDir(name: String): Path = {
    val dir = configDir.resolve(name)

    if (Files.notExists(dir)) {
      Files.createDirectories(dir)
    }

    val attributes = Files.readAttributes(dir, classOf[BasicFileAttributes])
    if (!attributes.isDirectory) {
      throw new IllegalStateException(s"$dir exists but is not a directory")
    }

    dir
  }

  private val fileLock = {
    val file = configDir.resolve("lock")
    val channel = FileChannel.open(file, CREATE, WRITE)
    val lock = channel.tryLock()

    if (lock == null) {
      throw new IllegalStateException(s"$workingDir already in use.")
    }

    lock
  }

  private implicit val auth: Auth = {
    val file = configDir.resolve("auth")

    def newAuth() = {
      println("Requesting Flickr authorization.")
      save(Flickr.requestAuth(), file)
    }

    if (Files.notExists(file)) {
      newAuth()
    } else {
      load[Auth](file, _ => {
        println("Stored authorization is invalid.")
        newAuth()
      })
    }
  }

  val PageSize = 500

  private val metadataDir = snifflDir("metadata")

  def saveMetadata(photo: SPhoto): SPhoto = {
    save(photo, metadataDir.resolve(photo.id))
  }

  def loadMetadata(photoId: String): SPhoto = {
    load[SPhoto](metadataDir.resolve(photoId))
  }

  def saveMetadataPage(page: PhotoList[Photo]): Seq[SPhoto] = {
    import scala.collection.JavaConverters._
    page.asScala.map(SPhoto(auth.getUser, _)).map { photo =>
      if (Files.notExists(metadataDir.resolve(photo.id))) {
        saveMetadata(photo)
      } else {
        loadMetadata(photo.id)
      }
    }
  }

  type Handler = (Int, Throwable) => Unit

  /**
    * Infinitely retry function f until success.
    */
  def stubbornly[T](f: () => T)(onFail: Handler = (_, _) => ()): T = {
    var tries = 0
    var result: Option[T] = None

    while (result.isEmpty) {
      try {
        result = Some(f())
      } catch {
        case NonFatal(ex) =>
          tries += 1
          onFail(tries, ex)
      }
    }

    result.get
  }

  def paged[T](page: Int => Page[T]): List[T] = {
    val result = mutable.ArrayBuffer.empty[T]

    val page1 = page(1)
    result ++= page1.contents

    if (page1.totalPages > 1) {
      val otherPages = mutable.ArrayBuffer.empty[Future[Page[T]]]
      for (p <- 2 to page1.totalPages) {
        otherPages += Future {
          page(p)
        }
      }

      otherPages.foreach { f =>
        val page = Await.result(f, Duration.Inf)
        result ++= page.contents
      }
    }

    result.toList
  }

  def photoMetadata: List[SPhoto] = paged { p =>
    stubbornly { () =>
      logger.debug(s"requesting photos page $p")
      val params = new SearchParameters
      params.setUserId(auth.getUser.getId)
      params.setSort(SearchParameters.DATE_POSTED_ASC)
      params.setExtras(Extras.ALL_EXTRAS)

      val photos = Flickr.photos.search(params, PageSize, p)
      val pageContents = saveMetadataPage(photos).toList

      new Page[SPhoto] {
        override def contents: List[SPhoto] = pageContents

        override def totalPages: Int = photos.getPages
      }
    } { (tries, ex) =>
      logger.info(s"Try $tries failed getting photos page $p", ex)
    }
  }

  def photosets: List[Photoset] = paged { p =>
    stubbornly { () =>
      logger.debug(s"requesting photosets page $p")
      val list =
        Flickr.photosets.getList(auth.getUser.getId, PageSize, p, null)
      val c = List.newBuilder[Photoset]
      list.getPhotosets.forEach(p => c += p)

      new Page[Photoset] {
        override def contents: List[Photoset] =
          list.getPhotosets.toArray(Array.empty[Photoset]).toList

        override def totalPages: Int = list.getPages
      }
    } { (tries, ex) =>
      logger.info(s"Try $tries failed getting photosets page $p", ex)
    }
  }

  def photos(photoset: Photoset): List[String] = paged { p =>
    stubbornly { () =>
      logger.debug(s"requesting ${photoset.getTitle} photos page $p")
      val list = Flickr.photosets.getPhotos(photoset.getId, PageSize, p)

      new Page[String] {
        override def contents: List[String] = {
          import scala.collection.JavaConverters._
          list.asScala.map(_.getId).toList
        }

        override def totalPages: Int = list.getPages
      }
    } { (tries, ex) =>
      logger.info(
        s"Try $tries failed getting ${photoset.getTitle} photos page $p",
        ex)
    }
  }

  private val cacheDir = snifflDir("cache")

  private val tmpDir = snifflDir("tmp")

  private def downloadPhoto(photo: SPhoto,
                            timeoutMinutes: Int): Option[SPhoto] = {
    val urlStr = photo.originalUrl
    val url = new URL(urlStr)
    val conn = url.openConnection().asInstanceOf[HttpsURLConnection]
    conn.setConnectTimeout(TimeUnit.MINUTES.toMillis(timeoutMinutes).toInt)
    conn.setReadTimeout(TimeUnit.MINUTES.toMillis(timeoutMinutes).toInt)
    conn.connect()
    conn.getResponseCode match {
      case 200 =>
        val filename = Option(conn.getHeaderField("Content-Disposition"))
          .flatMap(header => {
            Option(new ContentDisposition(header).getParameter("filename"))
          })
          .getOrElse(s"${photo.id}.${photo.format.getOrElse("jpg")}")

        val path = cacheDir.resolve(filename)
        val tmpFile = Files.createTempFile(tmpDir, "sniffl", "tmp")
        try {
          val startTs = System.currentTimeMillis()
          val stream = conn.getInputStream
          val size = Files.copy(stream, tmpFile, REPLACE_EXISTING)
          val time = System.currentTimeMillis() - startTs
          val rate = (size.toFloat / time.toFloat) * (1000.toFloat / 1024.toFloat)
          logger.info(
            f"Downloaded ${photo.media} ${photo.id} $size bytes in $time ms ($rate%2.2f KiB/s)")
          Files.move(tmpFile, path)
        } finally {
          if (Files.exists(tmpFile)) {
            Files.delete(tmpFile)
          }
        }
        val result = photo.copy(file = Some(path.toString))
        Some(saveMetadata(result))
      case 404 =>
        logger.info(
          s"Cannot download ${photo.media} ${photo.id} from $urlStr - download original at ${photo.pageUrl}")
        None
    }

  }

  private def doDownload(photo: SPhoto): Future[SPhoto] =
    Future {
      var tries = 0
      var result: Option[SPhoto] = Option(photo)

      while (result.isDefined && result.get.path.forall(Files.notExists(_))) {
        val startTs = System.currentTimeMillis()
        try {
          tries += 1
          logger.debug(s"Downloading ${photo.media} ${photo.id} (try $tries)")
          result = downloadPhoto(photo, tries)
        } catch {
          case NonFatal(ex) =>
            logger.info(
              s"Try $tries failed (after ${System
                .currentTimeMillis() - startTs} ms) retrieving ${photo.media} ${photo.id}",
              ex)
            result = Option(photo)
            Thread.sleep(
              TimeUnit.SECONDS.toMillis(Math.min(10, Random.nextInt(tries))))
        }
      }
      result.getOrElse(photo)
    }

  private val manualDir = snifflDir("manual")

  def photos(): Map[String, SPhoto] = {
    val photos: List[Future[SPhoto]] = photoMetadata map { photo =>
      photo.path match {
        case Some(path) if Files.exists(path) => Future.successful(photo)
        case _ => doDownload(photo)
      }
    }

    val results: List[SPhoto] = photos map { f =>
      Await.result(f, Duration.Inf)
    }

    val (fails, successes): (List[SPhoto], List[SPhoto]) = results partition {
      r =>
        r.path.forall(Files.notExists(_))
    }

    val manuals = mutable.ArrayBuffer.empty[SPhoto]

    fails foreach { photo =>
      val manualFile = if (Files.exists(manualDir)) {
        manualDir.toFile
          .listFiles((_, name) => name.startsWith(photo.id))
          .headOption
      } else {
        None
      }

      manualFile match {
        case Some(file) =>
          logger.debug(s"Using manually downloaded $file")
          manuals += saveMetadata(photo.copy(file = Some(file.toString)))
        case None =>
          logger.error(
            s"Cannot download ${photo.media} ${photo.id} -" +
              s" download original at ${photo.pageUrl} and save to $manualDir")
      }
    }

    Map((successes ++ manuals) map { photo =>
      photo.id -> photo
    }: _*)
  }

  def deleteDir(dir: Path): Unit = {
    if (Files.exists(dir)) {
      Files.walkFileTree(
        dir,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(
              dir: Path,
              exc: IOException): FileVisitResult = {
            Files.delete(dir)
            FileVisitResult.CONTINUE
          }
        }
      )
    }
  }

  def save[T](obj: T, path: Path): T = {
    logger.debug(s"saving $obj to $path")

    val objStream = new ObjectOutputStream(new FileOutputStream(path.toFile))
    try {
      objStream.writeObject(obj)
    } finally {
      objStream.close()
    }
    obj
  }

  def load[T](path: Path,
              recover: Throwable => T = (t: Throwable) => throw t): T = {
    logger.debug(s"loading $path")
    var objStream: Option[ObjectInputStream] = None
    try {
      objStream = Some(new ObjectInputStream(new FileInputStream(path.toFile)))
      objStream.get.readObject().asInstanceOf[T]
    } catch {
      case NonFatal(ex) => recover(ex)
    } finally {
      objStream.foreach(_.close())
    }
  }
}

trait Page[T] {
  def contents: List[T]
  def totalPages: Int
}
