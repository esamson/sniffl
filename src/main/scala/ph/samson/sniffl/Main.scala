package ph.samson.sniffl

import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import org.joda.time.DateTime
import ph.samson.sniffl.metadata.SPhoto

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object Main extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val workspaceDir =
      Paths.get(
        args.headOption.getOrElse(sys.props("user.dir"))
      )
    val logDir = workspaceDir.resolve(".sniffl").resolve("logs")
    System.setProperty("logdir", logDir.toAbsolutePath.toString)

    val workspace = new Workspace(workspaceDir)

    val photos = workspace.photos()
    logger.info(s"Done downloading ${photos.size} items")

    val photosList = photos.values.toList.sortBy(_.dateTaken.getTime)

    val names = mapNames(photosList)

    def byPhotoset() = {
      logger.debug("Organizing by photoset")

      implicit val requestPool = ExecutionContext.fromExecutorService(
        Executors.newFixedThreadPool(10,
                                     new ThreadFactoryBuilder()
                                       .setDaemon(true)
                                       .setNameFormat("request-%d")
                                       .build()))

      val covered = mutable.Set.empty[SPhoto]

      workspace.photosets.map { photoset =>
        Future {
          photoset -> workspace.photos(photoset)
        }
      } foreach { f =>
        val (photoset, members) = Await.result(f, Duration.Inf)
        val psDir = workspaceDir
          .resolve("photosets")
          .resolve(sanitizeFileName(photoset.getTitle))

        if (Files.notExists(psDir)) {
          Files.createDirectories(psDir)
        }

        members.flatMap(photos.get).foreach { photo =>
          covered += photo
          photo.path.foreach { file =>
            val dest = psDir.resolve(names(photo))
            if (Files.notExists(dest)) {
              Files.createLink(dest, file)
            }
          }
        }
      }

      requestPool.shutdown()

      logger.debug(s"Organized ${covered.size} items by photoset")
      // return photos not organized into any photoset
      photosList.filterNot(covered.contains)
    }

    def byDateTaken(photos: List[SPhoto]) = {
      logger.debug(s"Organizing ${photos.size} items by date taken")
      photos.foreach { photo =>
        photo.path.foreach { file =>
          val dtDir = Option(photo.dateTaken)
            .map(date => new DateTime(date))
            .map(dateTime => {
              workspaceDir
                .resolve("dateTaken")
                .resolve(dateTime.getYear.toString)
                .resolve(dateTime.getMonthOfYear.toString)
                .resolve(dateTime.getDayOfMonth.toString)
            })
            .getOrElse(workspaceDir.resolve("dateTaken").resolve("unknown"))
          if (Files.notExists(dtDir)) {
            Files.createDirectories(dtDir)
          }

          val dest = dtDir.resolve(names(photo))
          if (Files.notExists(dest)) {
            Files.createLink(dest, file)
          }
        }
      }
    }

    val unorganized: List[SPhoto] = byPhotoset()
    byDateTaken(unorganized)
  }

  def sanitizeFileName(name: String): String =
    name.replaceAll("[^-_.A-Za-z0-9]+", "_")

  /** Map to file names based on titles.
    *
    * @param photos photos
    * @return mapping of each photo to unique title.
    */
  def mapNames(photos: List[SPhoto]): Map[SPhoto, String] = {
    val nameMap: Map[SPhoto, (String, String)] = photos
      .map(photo => {
        val ext = sanitizeFileName(
          photo.file
            .map(_.split('.').last)
            .orElse(photo.format)
            .getOrElse("unknown")
        )

        val name = if (photo.title == null || photo.title.trim.isEmpty) {
          sanitizeFileName(photo.id).take(254 - ext.length)
        } else {
          sanitizeFileName(
            if (photo.title.toLowerCase.endsWith(ext.toLowerCase)) {
              photo.title.substring(0, photo.title.length - (ext.length + 1))
            } else {
              photo.title
            }
          )
        }
        photo -> (name -> ext)
      })
      .toMap

    val duplicateNames: Set[(String, String)] = nameMap
      .groupBy {
        case (_, (title, ext)) => title -> ext
      }
      .filter {
        case (_, group) => group.size > 1
      }
      .keySet

    nameMap.map {
      case (photo, (title, ext)) =>
        val mappedName = if (duplicateNames.contains(title -> ext)) {
          photo -> s"${title.take(253 - photo.id.length - ext.length)}.${photo.id}.$ext"
        } else {
          photo -> s"${title.take(254 - ext.length)}.$ext"
        }
        logger.debug(s"mapped name: $mappedName")
        mappedName
    }
  }
}
