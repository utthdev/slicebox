/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.metadata

import MetaDataProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesTypes
import se.nimsa.sbx.util.ExceptionCatching
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.log.SbxLog

class MetaDataServiceActor(dbProps: DbProps) extends Actor with ExceptionCatching {

  import context.system

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)

  log.info("Meta data service started")

  def receive = LoggingReceive {

    case AddDataset(dataset, source) =>
      catchAndReport {
        val image = addDataset(dataset, source)
        sender ! ImageAdded(image, source)
      }

    case DeleteImage(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          dao.imageById(imageId).foreach(propertiesDao.deleteFully(_))
          sender ! ImageDeleted(imageId)
        }
      }

    case msg: PropertiesRequest => catchAndReport {
      msg match {

        case AddSeriesTypeToSeries(seriesType, series) =>
          db.withSession { implicit session =>
            val seriesSeriesType = propertiesDao.insertSeriesSeriesType(SeriesSeriesType(series.id, seriesType.id))
            sender ! SeriesTypeAddedToSeries(seriesSeriesType)
          }

        case RemoveSeriesTypesFromSeries(series) =>
          db.withSession { implicit session =>
            propertiesDao.removeSeriesTypesForSeriesId(series.id)
            sender ! SeriesTypesRemovedFromSeries(series)
          }

        case GetSeriesTags =>
          sender ! SeriesTags(getSeriesTags)

        case GetSourceForSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.seriesSourceById(seriesId)
          }

        case GetSeriesTypesForSeries(seriesId) =>
          val seriesTypes = getSeriesTypesForSeries(seriesId)
          sender ! SeriesTypes(seriesTypes)

        case GetSeriesTagsForSeries(seriesId) =>
          val seriesTags = getSeriesTagsForSeries(seriesId)
          sender ! SeriesTags(seriesTags)

        case AddSeriesTagToSeries(seriesTag, seriesId) =>
          db.withSession { implicit session =>
            dao.seriesById(seriesId).getOrElse {
              throw new NotFoundException("Series not found")
            }
            val dbSeriesTag = propertiesDao.addAndInsertSeriesTagForSeriesId(seriesTag, seriesId)
            sender ! SeriesTagAddedToSeries(dbSeriesTag)
          }

        case RemoveSeriesTagFromSeries(seriesTagId, seriesId) =>
          db.withSession { implicit session =>
            propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTagId, seriesId)
            sender ! SeriesTagRemovedFromSeries(seriesId)
          }
      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds))
          }

        case GetStudies(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetSeries(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetImages(startIndex, count, seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(startIndex, count, seriesId))
          }

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetPatient(patientId) =>
          db.withSession { implicit session =>
            sender ! dao.patientById(patientId)
          }

        case GetStudy(studyId) =>
          db.withSession { implicit session =>
            sender ! dao.studyById(studyId)
          }

        case GetSingleSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.seriesById(seriesId)
          }

        case GetAllSeries =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.series)
          }

        case GetImage(imageId) =>
          db.withSession { implicit session =>
            sender ! dao.imageById(imageId)
          }

        case GetSingleFlatSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.flatSeriesById(seriesId)
          }

        case QueryPatients(query) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.queryPatients(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryStudies(query) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.queryStudies(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QuerySeries(query) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.querySeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryImages(query) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.queryImages(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryFlatSeries(query) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.queryFlatSeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case GetImagesForStudy(studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.seriesForStudy(0, 100000000, studyId, sourceRefs, seriesTypeIds, seriesTagIds)
              .flatMap(series => dao.imagesForSeries(0, 100000000, series.id)))
          }

        case GetImagesForPatient(patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.studiesForPatient(0, 100000000, patientId, sourceRefs, seriesTypeIds, seriesTagIds)
              .flatMap(study => propertiesDao.seriesForStudy(0, 100000000, study.id, sourceRefs, seriesTypeIds, seriesTagIds)
                .flatMap(series => dao.imagesForSeries(0, 100000000, series.id))))
          }

      }
    }

  }

  def getSeriesTags =
    db.withSession { implicit session =>
      propertiesDao.listSeriesTags
    }

  def getSeriesTypesForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      propertiesDao.seriesTypesForSeries(seriesId)
    }

  def getSeriesTagsForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      propertiesDao.seriesTagsForSeries(seriesId)
    }

  def addDataset(dataset: Attributes, source: Source): Image =
    db.withSession { implicit session =>

      val patient = datasetToPatient(dataset)
      val study = datasetToStudy(dataset)
      val series = datasetToSeries(dataset)
      val seriesSource = SeriesSource(-1, source)
      val image = datasetToImage(dataset)

      val dbPatient = dao.patientByNameAndID(patient)
        .getOrElse(dao.insert(patient))
      val dbStudy = dao.studyByUidAndPatient(study, dbPatient)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))
      val dbSeries = dao.seriesByUidAndStudy(series, dbStudy)
        .getOrElse(dao.insert(series.copy(studyId = dbStudy.id)))
      val dbSeriesSource = propertiesDao.seriesSourceById(dbSeries.id)
        .getOrElse(propertiesDao.insertSeriesSource(seriesSource.copy(id = dbSeries.id)))
      val dbImage = dao.imageByUidAndSeries(image, dbSeries)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))

      if (dbSeriesSource.source.sourceType != source.sourceType || dbSeriesSource.source.sourceId != source.sourceId)
        SbxLog.warn("Storage", s"Existing series source does not match source of added image (${dbSeriesSource.source} vs $source). Source of added image will be lost.")

      dbImage
    }
}

object MetaDataServiceActor {
  def props(dbProps: DbProps): Props = Props(new MetaDataServiceActor(dbProps))
}
