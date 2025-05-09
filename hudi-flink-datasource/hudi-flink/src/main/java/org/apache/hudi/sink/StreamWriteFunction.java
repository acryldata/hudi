/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.sink;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.model.HoodieFlinkInternalRow;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieOperation;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordLocation;
import org.apache.hudi.common.model.HoodieRecordMerger;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.util.HoodieRecordUtils;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.metrics.FlinkStreamWriteMetrics;
import org.apache.hudi.sink.buffer.BufferSizeDetector;
import org.apache.hudi.sink.buffer.TotalSizeTracer;
import org.apache.hudi.sink.common.AbstractStreamWriteFunction;
import org.apache.hudi.sink.event.WriteMetadataEvent;
import org.apache.hudi.sink.utils.PayloadCreation;
import org.apache.hudi.table.action.commit.FlinkWriteHelper;
import org.apache.hudi.util.RowDataToAvroConverters;
import org.apache.hudi.util.StreamerUtil;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;

/**
 * Sink function to write the data to the underneath filesystem.
 *
 * <p><h2>Work Flow</h2>
 *
 * <p>The function firstly buffers the data as a batch of {@link HoodieRecord}s,
 * It flushes(write) the records batch when the batch size exceeds the configured size {@link FlinkOptions#WRITE_BATCH_SIZE}
 * or the total buffer size exceeds the configured size {@link FlinkOptions#WRITE_TASK_MAX_SIZE}
 * or a Flink checkpoint starts. After a batch has been written successfully,
 * the function notifies its operator coordinator {@link StreamWriteOperatorCoordinator} to mark a successful write.
 *
 * <p><h2>The Semantics</h2>
 *
 * <p>The task implements exactly-once semantics by buffering the data between checkpoints. The operator coordinator
 * starts a new instant on the timeline when a checkpoint triggers, the coordinator checkpoints always
 * start before its operator, so when this function starts a checkpoint, a REQUESTED instant already exists.
 *
 * <p>The function process thread blocks data buffering after the checkpoint thread finishes flushing the existing data buffer until
 * the current checkpoint succeed and the coordinator starts a new instant. Any error triggers the job failure during the metadata committing,
 * when the job recovers from a failure, the write function re-send the write metadata to the coordinator to see if these metadata
 * can re-commit, thus if unexpected error happens during the instant committing, the coordinator would retry to commit when the job
 * recovers.
 *
 * <p><h2>Fault Tolerance</h2>
 *
 * <p>The operator coordinator checks and commits the last instant then starts a new one after a checkpoint finished successfully.
 * It rolls back any inflight instant before it starts a new instant, this means one hoodie instant only span one checkpoint,
 * the write function blocks data buffer flushing for the configured checkpoint timeout
 * before it throws exception, any checkpoint failure would finally trigger the job failure.
 *
 * <p>Note: The function task requires the input stream be shuffled by the file IDs.
 *
 * @see StreamWriteOperatorCoordinator
 */
public class StreamWriteFunction extends AbstractStreamWriteFunction<HoodieFlinkInternalRow> {

  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(StreamWriteFunction.class);

  /**
   * Write buffer as buckets for a checkpoint. The key is bucket ID.
   */
  private transient Map<String, DataBucket> buckets;

  protected transient BiFunction<List<HoodieRecord>, String, List<WriteStatus>> writeFunction;

  private transient HoodieRecordMerger recordMerger;

  protected final RowType rowType;

  protected transient Schema avroSchema;

  protected transient RowDataToAvroConverters.RowDataToAvroConverter converter;

  protected transient PayloadCreation payloadCreation;

  /**
   * Total size tracer.
   */
  private transient TotalSizeTracer tracer;

  /**
   * Metrics for flink stream write.
   */
  protected transient FlinkStreamWriteMetrics writeMetrics;

  /**
   * Constructs a StreamingSinkFunction.
   *
   * @param config The config options
   */
  public StreamWriteFunction(Configuration config, RowType rowType) {
    super(config);
    this.rowType = rowType;
  }

  @Override
  public void open(Configuration parameters) throws IOException {
    this.tracer = new TotalSizeTracer(this.config);
    initBuffer();
    initWriteFunction();
    initMergeClass();
    registerMetrics();
    preparePayload();
  }

  @Override
  public void snapshotState() {
    // Based on the fact that the coordinator starts the checkpoint first,
    // it would check the validity.
    // wait for the buffer data flush out and request a new instant
    flushRemaining(false);
  }

  @Override
  public void processElement(HoodieFlinkInternalRow record,
                             ProcessFunction<HoodieFlinkInternalRow, Object>.Context ctx,
                             Collector<Object> out) throws Exception {
    bufferRecord(record);
  }

  @Override
  public void close() {
    if (this.writeClient != null) {
      this.writeClient.close();
    }
  }

  /**
   * End input action for batch source.
   */
  public void endInput() {
    super.endInput();
    flushRemaining(true);
    this.writeClient.cleanHandles();
    this.writeStatuses.clear();
  }

  // -------------------------------------------------------------------------
  //  Getter/Setter
  // -------------------------------------------------------------------------
  @VisibleForTesting
  @SuppressWarnings("rawtypes")
  public Map<String, List<HoodieRecord>> getDataBuffer() {
    Map<String, List<HoodieRecord>> ret = new HashMap<>();
    for (Map.Entry<String, DataBucket> entry : buckets.entrySet()) {
      ret.put(entry.getKey(), convertToHoodieRecords(entry.getValue().getRecords()));
    }
    return ret;
  }

  // -------------------------------------------------------------------------
  //  Utilities
  // -------------------------------------------------------------------------

  private void initBuffer() {
    this.buckets = new LinkedHashMap<>();
  }

  private void initWriteFunction() {
    final String writeOperation = this.config.get(FlinkOptions.OPERATION);
    switch (WriteOperationType.fromValue(writeOperation)) {
      case INSERT:
        this.writeFunction = (records, instantTime) -> this.writeClient.insert(records, instantTime);
        break;
      case UPSERT:
      case DELETE: // shares the code path with UPSERT
      case DELETE_PREPPED:
        this.writeFunction = (records, instantTime) -> this.writeClient.upsert(records, instantTime);
        break;
      case INSERT_OVERWRITE:
        this.writeFunction = (records, instantTime) -> this.writeClient.insertOverwrite(records, instantTime);
        break;
      case INSERT_OVERWRITE_TABLE:
        this.writeFunction = (records, instantTime) -> this.writeClient.insertOverwriteTable(records, instantTime);
        break;
      default:
        throw new RuntimeException("Unsupported write operation : " + writeOperation);
    }
  }

  private void initMergeClass() {
    recordMerger = HoodieRecordUtils.mergerToPreCombineMode(writeClient.getConfig().getRecordMerger());
    LOG.info("init hoodie merge with class [{}]", recordMerger.getClass().getName());
  }

  protected void preparePayload() {
    this.avroSchema = StreamerUtil.getSourceSchema(this.config);
    this.converter = RowDataToAvroConverters.createConverter(this.rowType, this.config.getBoolean(FlinkOptions.WRITE_UTC_TIMEZONE));
    try {
      this.payloadCreation = PayloadCreation.instance(config);
    } catch (Exception ex) {
      throw new HoodieException("Failed payload creation in StreamWriteFunction", ex);
    }
  }

  /**
   * Data bucket.
   */
  protected static class DataBucket {
    private final List<HoodieFlinkInternalRow> records;
    private final BufferSizeDetector detector;

    private DataBucket(Double batchSize) {
      this.records = new ArrayList<>();
      this.detector = new BufferSizeDetector(batchSize);
    }

    public List<HoodieFlinkInternalRow> getRecords() {
      return records;
    }

    public boolean isEmpty() {
      return records.isEmpty();
    }

    public void reset() {
      this.records.clear();
      this.detector.reset();
    }
  }

  /**
   * Returns the bucket ID with the given value {@code value}.
   */
  private String getBucketID(String partitionPath, String fileId) {
    return StreamerUtil.generateBucketKey(partitionPath, fileId);
  }

  /**
   * Buffers the given record.
   *
   * <p>Flush the data bucket first if the bucket records size is greater than
   * the configured value {@link FlinkOptions#WRITE_BATCH_SIZE}.
   *
   * <p>Flush the max size data bucket if the total buffer size exceeds the configured
   * threshold {@link FlinkOptions#WRITE_TASK_MAX_SIZE}.
   *
   * @param record HoodieFlinkInternalRow
   */
  protected void bufferRecord(HoodieFlinkInternalRow record) {
    writeMetrics.markRecordIn();
    final String bucketID = getBucketID(record.getPartitionPath(), record.getFileId());

    DataBucket bucket = this.buckets.computeIfAbsent(bucketID,
        k -> new DataBucket(this.config.getDouble(FlinkOptions.WRITE_BATCH_SIZE)));
    bucket.records.add(record);

    boolean isFullBucket = bucket.detector.detect(record);
    boolean isFullBuffer = this.tracer.trace(bucket.detector.lastRecordSize);
    // update buffer metrics after tracing buffer size
    writeMetrics.setWriteBufferedSize(this.tracer.bufferSize);
    if (isFullBucket) {
      if (flushBucket(bucket)) {
        this.tracer.countDown(bucket.detector.totalSize);
        bucket.reset();
      }
    } else if (isFullBuffer) {
      // find the max size bucket and flush it out
      DataBucket bucketToFlush = this.buckets.values().stream()
          .max(Comparator.comparingLong(b -> b.detector.totalSize))
          .orElseThrow(NoSuchElementException::new);
      if (flushBucket(bucketToFlush)) {
        this.tracer.countDown(bucketToFlush.detector.totalSize);
        bucketToFlush.reset();
      } else {
        LOG.warn("The buffer size hits the threshold {}, but still flush the max size data bucket failed!", this.tracer.maxBufferSize);
      }
    }
  }

  private boolean hasData() {
    return !this.buckets.isEmpty()
        && this.buckets.values().stream().anyMatch(bucket -> !bucket.records.isEmpty());
  }

  private boolean flushBucket(DataBucket bucket) {
    String instant = instantToWrite(true);

    if (instant == null) {
      // in case there are empty checkpoints that has no input data
      LOG.info("No inflight instant when flushing data, skip.");
      return false;
    }

    ValidationUtils.checkState(!bucket.isEmpty(), "Data bucket to flush has no buffering records");
    final List<WriteStatus> writeStatus = writeRecords(instant, bucket.getRecords());
    final WriteMetadataEvent event = WriteMetadataEvent.builder()
        .taskID(taskID)
        .instantTime(instant) // the write instant may shift but the event still use the currentInstant.
        .writeStatus(writeStatus)
        .lastBatch(false)
        .endInput(false)
        .build();

    this.eventGateway.sendEventToCoordinator(event);
    writeStatuses.addAll(writeStatus);
    return true;
  }

  private void flushRemaining(boolean endInput) {
    writeMetrics.startDataFlush();
    this.currentInstant = instantToWrite(hasData());
    if (this.currentInstant == null) {
      // in case there are empty checkpoints that has no input data
      throw new HoodieException("No inflight instant when flushing data!");
    }
    final List<WriteStatus> writeStatus;
    if (!buckets.isEmpty()) {
      writeStatus = new ArrayList<>();
      this.buckets.values()
          // The records are partitioned by the bucket ID and each batch sent to
          // the writer belongs to one bucket.
          .forEach(bucket -> {
            if (!bucket.isEmpty()) {
              writeStatus.addAll(writeRecords(currentInstant, bucket.getRecords()));
              bucket.reset();
            }
          });
    } else {
      LOG.info("No data to write in subtask [{}] for instant [{}]", taskID, currentInstant);
      writeStatus = Collections.emptyList();
    }
    final WriteMetadataEvent event = WriteMetadataEvent.builder()
        .taskID(taskID)
        .instantTime(currentInstant)
        .writeStatus(writeStatus)
        .lastBatch(true)
        .endInput(endInput)
        .build();

    this.eventGateway.sendEventToCoordinator(event);
    this.buckets.clear();
    this.tracer.reset();
    this.writeClient.cleanHandles();
    this.writeStatuses.addAll(writeStatus);
    // blocks flushing until the coordinator starts a new instant
    this.confirming = true;

    writeMetrics.endDataFlush();
    writeMetrics.resetAfterCommit();
  }

  private void registerMetrics() {
    MetricGroup metrics = getRuntimeContext().getMetricGroup();
    writeMetrics = new FlinkStreamWriteMetrics(metrics);
    writeMetrics.registerMetrics();
  }

  protected List<WriteStatus> writeRecords(String instant, List<HoodieFlinkInternalRow> records) {
    writeMetrics.startFileFlush();
    List<WriteStatus> statuses = writeFunction.apply(deduplicateRecordsIfNeeded(convertToHoodieRecords(records)), instant);
    writeMetrics.endFileFlush();
    writeMetrics.increaseNumOfFilesWritten();
    return statuses;
  }

  protected  List<HoodieRecord> convertToHoodieRecords(List<HoodieFlinkInternalRow> records) {
    List<HoodieRecord> hoodieRecords = Arrays.asList(new HoodieRecord[records.size()]);
    for (int i = 0; i < records.size(); i++) {
      HoodieFlinkInternalRow record = records.get(i);
      RowData row = record.getRowData();
      // [HUDI-8969] Analyze how to write `RowData` directly
      GenericRecord gr = (GenericRecord) this.converter.convert(this.avroSchema, row);
      HoodieRecordPayload payload;
      try {
        payload = payloadCreation.createPayload(gr);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      HoodieRecord hoodieRecord =
          new HoodieAvroRecord<>(
              new HoodieKey(record.getRecordKey(), record.getPartitionPath()),
              payload,
              HoodieOperation.fromName(record.getOperationType()));
      hoodieRecord.unseal();
      hoodieRecord.setCurrentLocation(new HoodieRecordLocation(record.getInstantTime(), record.getFileId()));
      hoodieRecord.seal();
      hoodieRecords.set(i, hoodieRecord);
    }
    return hoodieRecords;
  }

  protected List<HoodieRecord> deduplicateRecordsIfNeeded(List<HoodieRecord> records) {
    if (config.getBoolean(FlinkOptions.PRE_COMBINE)) {
      return FlinkWriteHelper.newInstance()
          .deduplicateRecords(records, null, -1, this.writeClient.getConfig().getSchema(), this.writeClient.getConfig().getProps(), recordMerger);
    } else {
      return records;
    }
  }
}
