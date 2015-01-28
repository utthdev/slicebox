package se.vgregion.dicom

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol.AddDataset
import se.vgregion.dicom.DicomProtocol.DeleteImage
import se.vgregion.dicom.DicomProtocol.DeletePatient
import se.vgregion.dicom.DicomProtocol.DeleteSeries
import se.vgregion.dicom.DicomProtocol.DeleteStudy
import se.vgregion.dicom.DicomProtocol.FileName
import se.vgregion.dicom.DicomProtocol.GetAllImageFiles
import se.vgregion.dicom.DicomProtocol.GetAllImages
import se.vgregion.dicom.DicomProtocol.GetImageFiles
import se.vgregion.dicom.DicomProtocol.GetImages
import se.vgregion.dicom.DicomProtocol.GetPatients
import se.vgregion.dicom.DicomProtocol.GetSeries
import se.vgregion.dicom.DicomProtocol.GetStudies
import se.vgregion.dicom.DicomProtocol.ImageAdded
import se.vgregion.dicom.DicomProtocol.ImageFile
import se.vgregion.dicom.DicomProtocol.ImageFiles
import se.vgregion.dicom.DicomProtocol.ImageFilesDeleted
import se.vgregion.dicom.DicomProtocol.Images
import se.vgregion.dicom.DicomProtocol.MetaDataQuery
import se.vgregion.dicom.DicomProtocol.MetaDataUpdate
import se.vgregion.dicom.DicomProtocol.Patients
import se.vgregion.dicom.DicomProtocol.SeriesCollection
import se.vgregion.dicom.DicomProtocol.Studies
import DicomHierarchy.Equipment
import DicomHierarchy.FrameOfReference
import DicomHierarchy.Image
import DicomHierarchy.Patient
import DicomHierarchy.Series
import DicomHierarchy.Study
import DicomPropertyValue.AccessionNumber
import DicomPropertyValue.BodyPartExamined
import DicomPropertyValue.FrameOfReferenceUID
import DicomPropertyValue.ImageType
import DicomPropertyValue.Manufacturer
import DicomPropertyValue.Modality
import DicomPropertyValue.PatientBirthDate
import DicomPropertyValue.PatientID
import DicomPropertyValue.PatientName
import DicomPropertyValue.PatientSex
import DicomPropertyValue.ProtocolName
import DicomPropertyValue.SOPInstanceUID
import DicomPropertyValue.SeriesDate
import DicomPropertyValue.SeriesDescription
import DicomPropertyValue.SeriesInstanceUID
import DicomPropertyValue.StationName
import DicomPropertyValue.StudyDate
import DicomPropertyValue.StudyDescription
import DicomPropertyValue.StudyID
import DicomPropertyValue.StudyInstanceUID
import DicomUtil._
import se.vgregion.dicom.DicomProtocol.DatasetReceived

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
  }

  def receive = LoggingReceive {

    case DatasetReceived(dataset) =>
      storeDataset(dataset)
      log.info("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))

    case AddDataset(dataset) =>
      val image = storeDataset(dataset)
      sender ! ImageAdded(image)

    case msg: MetaDataUpdate => msg match {

      case DeleteImage(imageId) =>
        db.withSession { implicit session =>
          val imageFiles = dao.imageFilesForImageId(imageId)
          dao.deleteImageWithId(imageId)
          deleteFromStorage(imageFiles)
          sender ! ImageFilesDeleted(imageFiles)
        }

      case DeleteSeries(seriesId) =>
        db.withSession { implicit session =>
          val imageFiles = dao.imageFilesForSeriesId(seriesId)
          dao.deleteSeriesWithId(seriesId)
          deleteFromStorage(imageFiles)
          sender ! ImageFilesDeleted(imageFiles)
        }

      case DeleteStudy(studyId) =>
        db.withSession { implicit session =>
          val imageFiles = dao.imageFilesForStudyId(studyId)
          dao.deleteStudyWithId(studyId)
          deleteFromStorage(imageFiles)
          sender ! ImageFilesDeleted(imageFiles)
        }

      case DeletePatient(patientId) =>
        db.withSession { implicit session =>
          val imageFiles = dao.imageFilesForPatientId(patientId)
          dao.deletePatientWithId(patientId)
          deleteFromStorage(imageFiles)
          sender ! ImageFilesDeleted(imageFiles)
        }
    }

    case GetAllImageFiles =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.allImageFiles)
      }

    case GetImageFiles(imageId) =>
      db.withSession { implicit session =>
        sender ! ImageFiles(dao.imageFilesForImageId(imageId))
      }

    case msg: MetaDataQuery => msg match {
      case GetAllImages =>
        db.withSession { implicit session =>
          sender ! Images(dao.allImages)
        }

      case GetPatients =>
        db.withSession { implicit session =>
          sender ! Patients(dao.allPatients)
        }
      case GetStudies(patientId) =>
        db.withSession { implicit session =>
          sender ! Studies(dao.studiesForPatientId(patientId))
        }
      case GetSeries(studyId) =>
        db.withSession { implicit session =>
          sender ! SeriesCollection(dao.seriesForStudyId(studyId))
        }
      case GetImages(seriesId) =>
        db.withSession { implicit session =>
          sender ! Images(dao.imagesForSeriesId(seriesId))
        }

    }

  }

  def storeDataset(dataset: Attributes): Image = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)
    
    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      val dbPatient = dao.existingPatient(patient)
        .getOrElse(dao.insert(patient))
      
      val study = datasetToStudy(dataset)
      val dbStudy = dao.existingStudy(study)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))
        
      val equipment = datasetToEquipment(dataset)
      val dbEquipment = dao.existingEquipment(equipment)
        .getOrElse(dao.insert(equipment))
      
      val frameOfReference = datasetToFrameOfReference(dataset)
      val dbFrameOfReference = dao.existingFrameOfReference(frameOfReference)
        .getOrElse(dao.insert(frameOfReference))

      val series = datasetToSeries(dataset)
      val dbSeries = dao.existingSeries(series)
        .getOrElse(dao.insert(series.copy(
          studyId = dbStudy.id,
          equipmentId = dbEquipment.id,
          frameOfReferenceId = dbFrameOfReference.id)))
          
      val image = datasetToImage(dataset)
      val dbImage = dao.existingImage(image)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))
      
      val imageFile = ImageFile(-1, dbImage.id, FileName(name))
      val dbImageFile = dao.existingImageFile(imageFile)
        .getOrElse(dao.insert(imageFile))
      
      val anonymizedDataset = DicomAnonymization.anonymizeDataset(dataset)
      saveDataset(anonymizedDataset, storedPath)
      
      image
    }
  }

  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

  def deleteFromStorage(imageFiles: Seq[ImageFile]): Unit = imageFiles foreach (deleteFromStorage(_))
  def deleteFromStorage(imageFile: ImageFile): Unit = deleteFromStorage(Paths.get(imageFile.fileName.value))
  def deleteFromStorage(filePath: Path): Unit = {
    Files.delete(filePath)
    log.info("Deleted file " + filePath)
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

}

object DicomStorageActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DicomStorageActor(dbProps, storage))
}
