/*
 * Copyright 2017 Lars Edenbrandt
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

package se.nimsa.sbx.storage

import java.io.{ByteArrayOutputStream, InputStream}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomUtil._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Service that stores DICOM files on AWS S3.
  * @param s3Prefix prefix for keys
  * @param bucket S3 bucket
  * @param region aws region of the bucket
  */
class S3Storage(val bucket: String, val s3Prefix: String, val region: String) extends StorageService {

  val s3Client = new S3Facade(bucket, region)

  private def s3Id(image: Image): String =
    s3Id(imageName(image))

  private def s3Id(imageName: String): String =
    s3Prefix + "/" + imageName

  override def move(sourceImageName: String, targetImageName: String) = {
    s3Client.copy(sourceImageName, s3Id(targetImageName))
    s3Client.delete(sourceImageName)
  }

  override def storeDicomData(dicomData: DicomData, image: Image): Boolean = {
    val storedId = s3Id(image)
    val overwrite = s3Client.exists(storedId)
    try saveDicomDataToS3(dicomData, storedId) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    overwrite
  }

  private def saveDicomDataToS3(dicomData: DicomData, s3Key: String): Unit = {
    val os = new ByteArrayOutputStream()
    try saveDicomData(dicomData, os) catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException("Dicom data could not be stored", e)
    }
    val buffer = os.toByteArray
    s3Client.upload(s3Key, buffer)
  }

  override def deleteFromStorage(image: Image): Unit = s3Client.delete(s3Id(image))

  override def readDicomData(image: Image, withPixelData: Boolean): DicomData = {
    val s3InputStream = s3Client.get(s3Id(image))
    loadDicomData(s3InputStream, withPixelData)
  }

  override def readPngImageData(image: Image, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int)
                               (implicit system: ActorSystem, materializer: Materializer): Array[Byte] = {
    super.readPngImageData(fileSource(image), frameNumber, windowMin, windowMax, imageHeight)
  }

  override def imageAsInputStream(image: Image): InputStream = {
    val s3InputStream = s3Client.get(s3Id(image))
    s3InputStream
  }

  override def fileSink(name: String)(implicit actorSystem: ActorSystem, mat: Materializer, ec: ExecutionContext):  Sink[ByteString, Future[Done]] = {
    new S3Client(S3Facade.credentialsFromProviderChain(), region).multipartUpload(bucket, name).mapMaterializedValue(_.map(_ => Done))
  }

  override def fileSource(image: Image)(implicit actorSystem: ActorSystem, mat: Materializer): Source[ByteString, NotUsed] = {
    new S3Client(S3Facade.credentialsFromProviderChain(), region).download(bucket, s3Id(image))
  }
}
