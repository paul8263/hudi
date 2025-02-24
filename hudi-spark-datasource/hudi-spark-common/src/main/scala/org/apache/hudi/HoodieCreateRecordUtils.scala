/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.hudi.DataSourceWriteOptions.{INSERT_DROP_DUPS, PAYLOAD_CLASS_NAME, PRECOMBINE_FIELD}
import org.apache.hudi.avro.HoodieAvroUtils
import org.apache.hudi.common.config.TypedProperties
import org.apache.hudi.common.fs.FSUtils
import org.apache.hudi.common.model.{HoodieKey, HoodieRecord, HoodieRecordLocation, HoodieSparkRecord, WriteOperationType}
import org.apache.hudi.common.model.HoodieRecord.HOODIE_META_COLUMNS_NAME_TO_POS
import org.apache.hudi.common.util.StringUtils
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.keygen.constant.KeyGeneratorOptions
import org.apache.hudi.keygen.factory.HoodieSparkKeyGeneratorFactory
import org.apache.hudi.keygen.{BaseKeyGenerator, KeyGenUtils, KeyGenerator, SparkKeyGeneratorInterface}
import org.apache.spark.TaskContext
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.HoodieInternalRowUtils.getCachedUnsafeRowWriter
import org.apache.spark.sql.{DataFrame, HoodieInternalRowUtils}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions.mapAsJavaMap

/** Utility class for converting dataframe into RDD[HoodieRecord]. */
object HoodieCreateRecordUtils {
  private val log = LoggerFactory.getLogger(getClass)

  case class createHoodieRecordRddArgs(df: DataFrame,
                                       config: HoodieWriteConfig,
                                       parameters: Map[String, String],
                                       recordName: String,
                                       recordNameSpace: String,
                                       writerSchema: Schema,
                                       dataFileSchema: Schema,
                                       operation: WriteOperationType,
                                       instantTime: String,
                                       isPrepped: Boolean,
                                       sqlMergeIntoPrepped: Boolean)

  def createHoodieRecordRdd(args: createHoodieRecordRddArgs) = {
    val df = args.df
    val config = args.config
    val parameters = args.parameters
    val recordName = args.recordName
    val recordNameSpace = args.recordNameSpace
    val writerSchema = args.writerSchema
    val dataFileSchema = args.dataFileSchema
    val operation = args.operation
    val instantTime = args.instantTime
    val isPrepped = args.isPrepped
    val sqlMergeIntoPrepped = args.sqlMergeIntoPrepped

    val shouldDropPartitionColumns = config.getBoolean(DataSourceWriteOptions.DROP_PARTITION_COLUMNS)
    val recordType = config.getRecordMerger.getRecordType
    val autoGenerateRecordKeys: Boolean = !parameters.containsKey(KeyGeneratorOptions.RECORDKEY_FIELD_NAME.key())

    val shouldCombine = if (!isPrepped && WriteOperationType.isInsert(operation)) {
      parameters(INSERT_DROP_DUPS.key()).toBoolean ||
        parameters.getOrElse(
          HoodieWriteConfig.COMBINE_BEFORE_INSERT.key(),
          HoodieWriteConfig.COMBINE_BEFORE_INSERT.defaultValue()
        ).toBoolean
    } else if (!isPrepped && WriteOperationType.isUpsert(operation)) {
      parameters.getOrElse(
        HoodieWriteConfig.COMBINE_BEFORE_UPSERT.key(),
        HoodieWriteConfig.COMBINE_BEFORE_UPSERT.defaultValue()
      ).toBoolean
    } else {
      !isPrepped
    }

    // NOTE: Avro's [[Schema]] can't be effectively serialized by JVM native serialization framework
    //       (due to containing cyclic refs), therefore we have to convert it to string before
    //       passing onto the Executor
    val dataFileSchemaStr = dataFileSchema.toString

    log.debug(s"Creating HoodieRecords (as $recordType)")

    recordType match {
      case HoodieRecord.HoodieRecordType.AVRO =>
        // avroRecords will contain meta fields when isPrepped is true.
        val avroRecords: RDD[GenericRecord] = HoodieSparkUtils.createRdd(df, recordName, recordNameSpace,
          Some(writerSchema))

        avroRecords.mapPartitions(it => {
          val sparkPartitionId = TaskContext.getPartitionId()
          val keyGenProps = new TypedProperties(config.getProps)
          if (autoGenerateRecordKeys) {
            keyGenProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_PARTITION_ID_CONFIG, String.valueOf(sparkPartitionId))
            keyGenProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_INSTANT_TIME_CONFIG, instantTime)
          }
          val keyGenerator : Option[BaseKeyGenerator] = if (isPrepped) None else Some(HoodieSparkKeyGeneratorFactory.createKeyGenerator(keyGenProps).asInstanceOf[BaseKeyGenerator])
          val dataFileSchema = new Schema.Parser().parse(dataFileSchemaStr)
          val consistentLogicalTimestampEnabled = parameters.getOrElse(
            DataSourceWriteOptions.KEYGENERATOR_CONSISTENT_LOGICAL_TIMESTAMP_ENABLED.key(),
            DataSourceWriteOptions.KEYGENERATOR_CONSISTENT_LOGICAL_TIMESTAMP_ENABLED.defaultValue()).toBoolean

          // handle dropping partition columns
          var validatePreppedRecord = true
          it.map { avroRec =>
            if (validatePreppedRecord && isPrepped) {
              // For prepped records, check the first record to make sure it has meta fields set.
              validateMetaFieldsInAvroRecords(avroRec)
              validatePreppedRecord = false
            }

            val (hoodieKey: HoodieKey, recordLocation: Option[HoodieRecordLocation]) = HoodieCreateRecordUtils.getHoodieKeyAndMaybeLocationFromAvroRecord(keyGenerator, avroRec,
              isPrepped, sqlMergeIntoPrepped)
            val avroRecWithoutMeta: GenericRecord = if (isPrepped || sqlMergeIntoPrepped) {
              HoodieAvroUtils.rewriteRecord(avroRec, HoodieAvroUtils.removeMetadataFields(dataFileSchema))
            } else {
              avroRec
            }

            val processedRecord = if (shouldDropPartitionColumns) {
              HoodieAvroUtils.rewriteRecord(avroRecWithoutMeta, dataFileSchema)
            } else {
              avroRecWithoutMeta
            }

            val hoodieRecord = if (shouldCombine) {
              val orderingVal = HoodieAvroUtils.getNestedFieldVal(avroRec, config.getString(PRECOMBINE_FIELD),
                false, consistentLogicalTimestampEnabled).asInstanceOf[Comparable[_]]
              DataSourceUtils.createHoodieRecord(processedRecord, orderingVal, hoodieKey,
                config.getString(PAYLOAD_CLASS_NAME), recordLocation)
            } else {
              DataSourceUtils.createHoodieRecord(processedRecord, hoodieKey,
                config.getString(PAYLOAD_CLASS_NAME), recordLocation)
            }
            hoodieRecord
          }
        }).toJavaRDD()

      case HoodieRecord.HoodieRecordType.SPARK =>
        val dataFileSchema = new Schema.Parser().parse(dataFileSchemaStr)
        val dataFileStructType = HoodieInternalRowUtils.getCachedSchema(dataFileSchema)
        val writerStructType = HoodieInternalRowUtils.getCachedSchema(writerSchema)
        val sourceStructType = df.schema

        df.queryExecution.toRdd.mapPartitions { it =>
          val sparkPartitionId = TaskContext.getPartitionId()
          val keyGenProps = new TypedProperties(config.getProps)
          if (autoGenerateRecordKeys) {
            keyGenProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_PARTITION_ID_CONFIG, String.valueOf(sparkPartitionId))
            keyGenProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_INSTANT_TIME_CONFIG, instantTime)
          }
          val sparkKeyGenerator : Option[SparkKeyGeneratorInterface] = if (isPrepped) None else Some(HoodieSparkKeyGeneratorFactory.createKeyGenerator(keyGenProps).asInstanceOf[SparkKeyGeneratorInterface])
          val targetStructType = if (shouldDropPartitionColumns) dataFileStructType else writerStructType
          val finalStructType = if (isPrepped) {
            val fieldsToExclude = HoodieRecord.HOODIE_META_COLUMNS_WITH_OPERATION.toArray()
            StructType(targetStructType.fields.filterNot(field => fieldsToExclude.contains(field.name)))
          } else {
            targetStructType
          }
          // NOTE: To make sure we properly transform records
          val finalStructTypeRowWriter = getCachedUnsafeRowWriter(sourceStructType, finalStructType)

          var validatePreppedRecord = true
          it.map { sourceRow =>
            if (isPrepped) {
              // For prepped records, check the record schema to make sure it has meta fields set.
              validateMetaFieldsInSparkRecords(sourceStructType)
              validatePreppedRecord = false
            }
            val (key: HoodieKey, recordLocation: Option[HoodieRecordLocation]) = HoodieCreateRecordUtils.getHoodieKeyAndMayBeLocationFromSparkRecord(sparkKeyGenerator, sourceRow, sourceStructType, isPrepped, sqlMergeIntoPrepped)

            val targetRow = finalStructTypeRowWriter(sourceRow)
            var hoodieSparkRecord = new HoodieSparkRecord(key, targetRow, dataFileStructType, false)
            if (recordLocation.isDefined) {
              hoodieSparkRecord.setCurrentLocation(recordLocation.get)
            }
            hoodieSparkRecord
          }
        }.toJavaRDD().asInstanceOf[JavaRDD[HoodieRecord[_]]]
    }
  }

  private def validateMetaFieldsInSparkRecords(schema: StructType): Boolean = {
    if (!schema.fieldNames.contains(HoodieRecord.COMMIT_TIME_METADATA_FIELD)
      || !schema.fieldNames.contains(HoodieRecord.COMMIT_SEQNO_METADATA_FIELD)
      || !schema.fieldNames.contains(HoodieRecord.RECORD_KEY_METADATA_FIELD)
      || !schema.fieldNames.contains(HoodieRecord.PARTITION_PATH_METADATA_FIELD)
      || !schema.fieldNames.contains(HoodieRecord.FILENAME_METADATA_FIELD)) {
      throw new HoodieException("Metadata fields missing from spark prepared record.")
    }
    true
  }

  def validateMetaFieldsInAvroRecords(avroRec: GenericRecord): Unit = {
    val avroSchema = avroRec.getSchema;
    if (avroSchema.getField(HoodieRecord.RECORD_KEY_METADATA_FIELD) == null
      || avroSchema.getField(HoodieRecord.COMMIT_TIME_METADATA_FIELD) == null
      || avroSchema.getField(HoodieRecord.COMMIT_SEQNO_METADATA_FIELD) == null
      || avroSchema.getField(HoodieRecord.PARTITION_PATH_METADATA_FIELD) == null
      || avroSchema.getField(HoodieRecord.FILENAME_METADATA_FIELD) == null) {
      throw new HoodieException("Metadata fields missing from avro prepared record.")
    }
  }

  def getHoodieKeyAndMaybeLocationFromAvroRecord(keyGenerator: Option[BaseKeyGenerator], avroRec: GenericRecord,
                                                 isPrepped: Boolean, sqlMergeIntoPrepped: Boolean): (HoodieKey, Option[HoodieRecordLocation]) = {
    //use keygen for sqlMergeIntoPrepped recordKey and partitionPath because the keygenerator handles
    //fetching from the meta fields if they are populated and otherwise doing keygen
    val recordKey = if (isPrepped) {
      avroRec.get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString
    } else {
      keyGenerator.get.getRecordKey(avroRec)
    }

    val partitionPath = if (isPrepped) {
      avroRec.get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString
    } else {
      keyGenerator.get.getPartitionPath(avroRec)
    }

    val hoodieKey = new HoodieKey(recordKey, partitionPath)
    val instantTime: Option[String] = if (isPrepped || sqlMergeIntoPrepped) {
      Option(avroRec.get(HoodieRecord.COMMIT_TIME_METADATA_FIELD)).map(_.toString)
    }
    else {
      None
    }
    val fileName: Option[String] = if (isPrepped || sqlMergeIntoPrepped) {
      Option(avroRec.get(HoodieRecord.FILENAME_METADATA_FIELD)).map(_.toString)
    }
    else {
      None
    }
    val recordLocation: Option[HoodieRecordLocation] = if (instantTime.isDefined && fileName.isDefined) {
      val fileId = FSUtils.getFileId(fileName.get)
      Some(new HoodieRecordLocation(instantTime.get, fileId))
    } else {
      None
    }
    (hoodieKey, recordLocation)
  }

  def getHoodieKeyAndMayBeLocationFromSparkRecord(sparkKeyGenerator: Option[SparkKeyGeneratorInterface],
                                                  sourceRow: InternalRow, schema: StructType,
                                                  isPrepped: Boolean, sqlMergeIntoPrepped: Boolean): (HoodieKey, Option[HoodieRecordLocation]) = {
    //use keygen for sqlMergeIntoPrepped recordKey and partitionPath because the keygenerator handles
    //fetching from the meta fields if they are populated and otherwise doing keygen
    val recordKey = if (isPrepped) {
      sourceRow.getString(HoodieRecord.RECORD_KEY_META_FIELD_ORD)
    } else {
      sparkKeyGenerator.get.getRecordKey(sourceRow, schema).toString
    }

    val partitionPath = if (isPrepped) {
      sourceRow.getString(HoodieRecord.PARTITION_PATH_META_FIELD_ORD)
    } else {
      sparkKeyGenerator.get.getPartitionPath(sourceRow, schema).toString
    }

    val instantTime: Option[String] = if (isPrepped || sqlMergeIntoPrepped) {
      Option(sourceRow.getString(HoodieRecord.COMMIT_TIME_METADATA_FIELD_ORD))
    } else {
      None
    }

    val fileName: Option[String] = if (isPrepped || sqlMergeIntoPrepped) {
      Option(sourceRow.getString(HoodieRecord.FILENAME_META_FIELD_ORD))
    } else {
      None
    }

    val recordLocation: Option[HoodieRecordLocation] = if (instantTime.isDefined && fileName.isDefined) {
      val fileId = FSUtils.getFileId(fileName.get)
      Some(new HoodieRecordLocation(instantTime.get, fileId))
    } else {
      None
    }

    (new HoodieKey(recordKey, partitionPath), recordLocation)
  }
}
