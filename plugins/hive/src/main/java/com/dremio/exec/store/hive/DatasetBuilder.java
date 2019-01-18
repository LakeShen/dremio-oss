/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.exec.store.hive;

import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_STORAGE;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.RCFileInputFormat;
import org.apache.hadoop.hive.ql.io.avro.AvroContainerInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSplit;
import org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat;
import org.apache.hadoop.hive.ql.metadata.HiveStorageHandler;
import org.apache.hadoop.hive.ql.metadata.HiveUtils;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.HiveDecimalUtils;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.thrift.TException;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.DateTimes;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.catalog.ColumnCountTooLargeException;
import com.dremio.exec.planner.cost.ScanCostFactor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.TimedRunnable;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.exec.store.dfs.implicit.DecimalTools;
import com.dremio.exec.store.hive.exec.HiveReaderProtoUtil;
import com.dremio.hive.proto.HiveReaderProto.FileSystemCachedEntity;
import com.dremio.hive.proto.HiveReaderProto.FileSystemPartitionUpdateKey;
import com.dremio.hive.proto.HiveReaderProto.HiveReadSignature;
import com.dremio.hive.proto.HiveReaderProto.HiveReadSignatureType;
import com.dremio.hive.proto.HiveReaderProto.HiveSplitXattr;
import com.dremio.hive.proto.HiveReaderProto.HiveTableXattr;
import com.dremio.hive.proto.HiveReaderProto.PartitionProp;
import com.dremio.hive.proto.HiveReaderProto.Prop;
import com.dremio.hive.proto.HiveReaderProto.ReaderType;
import com.dremio.hive.proto.HiveReaderProto.SerializedInputSplit;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceTableDefinition;
import com.dremio.service.namespace.dataset.proto.Affinity;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetSplit;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.PartitionValue;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import com.dremio.service.namespace.dataset.proto.ScanStatsType;
import com.dremio.service.namespace.proto.EntityId;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.protostuff.ByteString;

class DatasetBuilder implements SourceTableDefinition {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DatasetBuilder.class);
  private static final int HIVE_SPLITS_GENERATOR_PARALLELISM = 16;
  private static final String EMPTY_STRING = "";

  private final HiveClient client;
  private final NamespaceKey datasetPath;
  private final String user;
  private final HiveConf hiveConf;

  private Properties tableProperties;
  private final String dbName;
  private final String tableName;
  private Table table;
  private DatasetConfig datasetConfig;
  private boolean built = false;
  private List<DatasetSplit> splits = new ArrayList<>();
  private final boolean ignoreAuthzErrors;
  private final int maxMetadataLeafColumns;

  private final StatsEstimationParameters statsParams;


  private DatasetBuilder(HiveClient client, String user, NamespaceKey datasetPath, boolean ignoreAuthzErrors,
      int maxMetadataLeafColumns, StatsEstimationParameters statsParams, HiveConf hiveConf, String dbName,
      String tableName, Table table, DatasetConfig oldConfig) {
    if(oldConfig == null) {
      datasetConfig = new DatasetConfig()
          .setPhysicalDataset(new PhysicalDataset())
          .setId(new EntityId().setId(UUID.randomUUID().toString()));
    } else {
      datasetConfig = oldConfig;
      // We're rewriting the read definition. Delete the old one.
      oldConfig.setReadDefinition(null);
    }
    this.client = client;
    this.user = user;
    this.datasetPath = datasetPath;
    this.hiveConf = hiveConf;
    this.table = table;
    this.dbName = dbName;
    this.tableName = tableName;
    this.ignoreAuthzErrors = ignoreAuthzErrors;
    this.maxMetadataLeafColumns = maxMetadataLeafColumns;
    this.statsParams = statsParams;
  }

  /**
   * Set of parameters controlling the process of estimating records in a hive table/partition
   */
  static class StatsEstimationParameters {
    private final boolean useMetastoreStats;
    private final int listSizeEstimate;
    private final int varFieldSizeEstimate;

    /**
     * @param useMetastoreStats Whether to use stats in metastore or estimate based on filesize/filetype/record size
     * @param listSizeEstimate Estimated number of elements in a list data type columns
     * @param varFieldSizeEstimate Estimated size of variable width columns
     */
    StatsEstimationParameters(final boolean useMetastoreStats, final int listSizeEstimate, final int varFieldSizeEstimate) {
      this.useMetastoreStats = useMetastoreStats;
      this.listSizeEstimate = listSizeEstimate;
      this.varFieldSizeEstimate = varFieldSizeEstimate;
    }

    public boolean useMetastoreStats() {
      return useMetastoreStats;
    }

    public int getListSizeEstimate() {
      return listSizeEstimate;
    }

    public int getVarFieldSizeEstimate() {
      return varFieldSizeEstimate;
    }
  }

  /**
   * @return null if datasetPath is not canonical and couldn't find a corresponding table in the source
   */
  static DatasetBuilder getDatasetBuilder(
      HiveClient client,
      String user,
      NamespaceKey datasetPath,
      boolean isCanonicalDatasetPath,
      boolean ignoreAuthzErrors,
      int maxMetadataLeafColumns,
      StatsEstimationParameters statsParams,
      HiveConf hiveConf,
      DatasetConfig oldConfig) throws TException {
    final List<String> noSourceSchemaPath =
      datasetPath.getPathComponents().subList(1, datasetPath.getPathComponents().size());

    // extract database and table names from dataset path
    final String dbName;
    final String tableName;
    switch (noSourceSchemaPath.size()) {
    case 1:
      dbName = "default";
      tableName = noSourceSchemaPath.get(0);
      break;
    case 2:
      dbName = noSourceSchemaPath.get(0);
      tableName = noSourceSchemaPath.get(1);
      break;
    default:
      //invalid.
      return null;
    }

    // if the dataset path is not canonized we need to get it from the source
    final Table table;
    final String canonicalTableName;
    final String canonicalDbName;
    if (isCanonicalDatasetPath) {
      canonicalDbName = dbName;
      canonicalTableName = tableName;
      table = null;
    } else {
      // passed datasetPath is not canonical, we need to get it from the source
      table = client.getTable(dbName, tableName, ignoreAuthzErrors);
      if(table == null){
        return null;
      }
      canonicalTableName = table.getTableName();
      canonicalDbName = table.getDbName();
    }

    final List<String> canonicalDatasetPath = Lists.newArrayList(datasetPath.getRoot(), canonicalDbName, canonicalTableName);
    return new DatasetBuilder(client, user, new NamespaceKey(canonicalDatasetPath), ignoreAuthzErrors, maxMetadataLeafColumns,
    statsParams, hiveConf, canonicalDbName, canonicalTableName, table, oldConfig);
  }

  @Override
  public NamespaceKey getName() {
    return datasetPath;
  }

  @Override
  public DatasetConfig getDataset() throws Exception {
    buildIfNecessary();
    return datasetConfig;
  }

  @Override
  public List<DatasetSplit> getSplits() throws Exception {
    buildIfNecessary();
    return ImmutableList.copyOf(splits);
  }

  private void buildIfNecessary() throws Exception {
    if(built){
      return;
    }
    if(table == null){
      table = client.getTable(dbName, tableName, ignoreAuthzErrors);
      if(table == null){
        throw UserException.dataReadError().message("Initially found table %s.%s but then failed to retrieve from Hive metastore.", dbName, tableName).build(logger);
      }
    }

    // store table properties.
    tableProperties = MetaStoreUtils.getSchema(table.getSd(), table.getSd(), table.getParameters(), table.getDbName(), table.getTableName(), table.getPartitionKeys());

    int leafFieldCounter = 0;
    List<Field> fields = new ArrayList<>();
    List<FieldSchema> hiveFields = table.getSd().getCols();
    for(FieldSchema hiveField : hiveFields) {
      Field f = HiveSchemaConverter.getArrowFieldFromHivePrimitiveType(hiveField.getName(), TypeInfoUtils.getTypeInfoFromTypeString(hiveField.getType()));
      if (f != null) {
        fields.add(f);
        leafFieldCounter = leafFieldCounter(leafFieldCounter);
      }
    }

    final List<String> partitionColumns = new ArrayList<>();
    for (FieldSchema field : table.getPartitionKeys()) {
      Field f = HiveSchemaConverter.getArrowFieldFromHivePrimitiveType(field.getName(),
              TypeInfoUtils.getTypeInfoFromTypeString(field.getType()));
      if (f != null) {
        fields.add(f);
        leafFieldCounter = leafFieldCounter(leafFieldCounter);
        partitionColumns.add(field.getName());
      }
    }

    final BatchSchema batchSchema = BatchSchema.newBuilder().addFields(fields).build();

    HiveTableXattr.Builder tableExtended = HiveTableXattr.newBuilder().addAllTableProperty(fromProperties(tableProperties));

    if(table.getSd().getInputFormat() != null){
      tableExtended.setInputFormat(table.getSd().getInputFormat());
    }

    String storageHandler = table.getParameters().get(META_TABLE_STORAGE);
    if(storageHandler != null){
      tableExtended.setStorageHandler(storageHandler);
    }

    tableExtended.setSerializationLib(table.getSd().getSerdeInfo().getSerializationLib());
    tableExtended.setTableHash(getHash(table));

    datasetConfig
      .setFullPathList(datasetPath.getPathComponents())
      .setType(DatasetType.PHYSICAL_DATASET)
      .setName(tableName)
      .setOwner(user)
      .setPhysicalDataset(new PhysicalDataset())
      .setRecordSchema(batchSchema.toByteString())
      .setReadDefinition(new ReadDefinition()
        .setPartitionColumnsList(partitionColumns)
        .setSortColumnsList(FluentIterable.from(table.getSd().getSortCols())
          .transform(order -> order.getCol()).toList())
        .setLastRefreshDate(System.currentTimeMillis())
        .setExtendedProperty(ByteString.copyFrom(tableExtended.build().toByteArray()))
        .setReadSignature(null)
      );

    final int estimatedRecordSize =
        batchSchema.estimateRecordSize(statsParams.getListSizeEstimate(), statsParams.getVarFieldSizeEstimate());

    buildSplits(tableExtended, dbName, tableName, estimatedRecordSize);
    HiveReaderProtoUtil.encodePropertiesAsDictionary(tableExtended);
    // reset the extended properties since buildSplits() may change them.
    datasetConfig.getReadDefinition().setExtendedProperty(ByteString.copyFrom(tableExtended.build().toByteArray()));

    built = true;

  }

  /**
   * Get the stats from table properties. If not found -1 is returned for each stats field.
   * CAUTION: stats may not be up-to-date with the underlying data. It is always good to run the ANALYZE command on
   * Hive table to have up-to-date stats.
   * @param properties
   * @return
   */
  private HiveStats getStatsFromProps(final Properties properties) {
    long numRows = -1;
    long sizeInBytes = -1;
    try {
      final String numRowsProp = properties.getProperty(StatsSetupConst.ROW_COUNT);
      if (numRowsProp != null) {
          numRows = Long.valueOf(numRowsProp);
      }

      final String sizeInBytesProp = properties.getProperty(StatsSetupConst.TOTAL_SIZE);
      if (sizeInBytesProp != null) {
        sizeInBytes = Long.valueOf(sizeInBytesProp);
      }
    } catch (final NumberFormatException e) {
      logger.error("Failed to parse Hive stats in metastore.", e);
      // continue with the defaults.
    }

    return new HiveStats(numRows, sizeInBytes);
  }

  private SerializedInputSplit serialize(InputSplit split) throws IOException{
    ByteArrayDataOutput output = ByteStreams.newDataOutput();
    split.write(output);
    return SerializedInputSplit.newBuilder()
      .setInputSplitClass(split.getClass().getName())
      .setInputSplit(com.google.protobuf.ByteString.copyFrom(output.toByteArray())).build();
  }

  private boolean allowParquetNative(boolean currentStatus, Class<? extends InputFormat<?, ?>> clazz){
    return currentStatus && MapredParquetInputFormat.class.equals(clazz);
  }

  private boolean isRecursive(Properties properties){
    return "true".equalsIgnoreCase(properties.getProperty("mapred.input.dir.recursive", "false")) &&
        "true".equalsIgnoreCase(properties.getProperty("hive.mapred.supports.subdirectories", "false"));
  }


  private class HiveSplitWork {
    List<DatasetSplit> splits;
    HiveStats hiveStats;

    public HiveSplitWork(List<DatasetSplit> splits, HiveStats hiveStats) {
      this.splits = splits;
      this.hiveStats = hiveStats;
    }

    public List<DatasetSplit> getSplits() {
      return splits;
    }

    public HiveStats getHiveStats() {
      return hiveStats;
    }
  }

  private class HiveSplitsGenerator extends TimedRunnable<HiveSplitWork> {
    private final InputFormat<?, ?> format;
    private final StorageDescriptor storageDescriptor;
    private final HiveStats totalStats;
    private final Partition partition;
    private final int partitionId;
    private final int estimatedRecordSize;

    public HiveSplitsGenerator(final InputFormat<?, ?> format, StorageDescriptor storageDescriptor,
                               final int estimatedRecordSize, HiveStats totalStats, Partition partition, int partitionId) {
      this.format = format;
      this.storageDescriptor = storageDescriptor;
      this.estimatedRecordSize = estimatedRecordSize;
      this.totalStats = totalStats;
      this.partition = partition;
      this.partitionId = partitionId;
    }

    @Override
    protected HiveSplitWork runInner() throws Exception {
      final JobConf job = new JobConf(hiveConf);
      addConfToJob(job, tableProperties);
      if (partition != null) {
        addConfToJob(job, getPartitionMetadata(partition, table));
      }
      addInputPath(storageDescriptor, job);

      List<DatasetSplit> splits = Lists.newArrayList();
      long totalEstimatedRecords = 0;

      InputSplit[] inputSplits = format.getSplits(job, 1);
      long inputSplitSizes[] = new long[inputSplits.length];
      double totalSize = 0;
      for (int i = 0; i < inputSplits.length; i++) {
        final long size = getInputSplitLength(inputSplits[i], job);
        totalSize += size;
        inputSplitSizes[i] = size;
      }
      int id = 0;
      for (final InputSplit inputSplit : inputSplits) {

        HiveSplitXattr.Builder splitAttr = HiveSplitXattr.newBuilder();

        splitAttr.setPartitionId(partitionId);
        splitAttr.setInputSplit(serialize(inputSplit));

        DatasetSplit split = new DatasetSplit();

        String splitKey =
            partition == null ? table.getSd().getLocation() + "__" + id : partition.getSd().getLocation() + "__" + id;
        split.setSplitKey(splitKey);
        final long length = inputSplitSizes[id];
        split.setSize(length);
        split.setPartitionValuesList(getPartitions(table, partition));
        split.setAffinitiesList(FluentIterable.of(
            inputSplit.getLocations()).transform((input) -> new Affinity().setHost(input).setFactor((double) length)
        ).toList());

        long splitEstimatedRecords = findRowCountInSplit(statsParams, totalStats, length/totalSize,
            length, format, estimatedRecordSize);
        totalEstimatedRecords += splitEstimatedRecords;
        split.setRowCount(splitEstimatedRecords);

        split.setExtendedProperty(ByteString.copyFrom(splitAttr.build().toByteArray()));
        splits.add(split);
        id++;
      }
      return new HiveSplitWork(splits, new HiveStats(totalEstimatedRecords, (long) totalSize));
    }

    @Override
    protected IOException convertToIOException(Exception e) {
      if (partition != null) {
        return new IOException("Failure while trying to get splits for partition " + partition.getSd().getLocation(), e);
      } else {
        return new IOException("Failure while trying to get splits for table " + tableName, e);
      }
    }
  }

  /**
   * Helper method that returns the size of the {@link InputSplit}. For non-transactional tables, the size is straight
   * forward. For transactional tables (currently only supported in ORC format), length need to be derived by
   * fetching the file status of the delta files.
   *
   * Logic for this function is derived from {@link OrcInputFormat#getRecordReader(InputSplit, JobConf, Reporter)}
   *
   * @param split
   * @param conf
   * @return
   * @throws IOException
   */
  private long getInputSplitLength(final InputSplit split, final Configuration conf) throws IOException {
    if (!(split instanceof OrcSplit)) {
      return split.getLength();
    }

    final OrcSplit orcSplit = (OrcSplit) split;

    if (!orcSplit.isAcid()) {
      return split.getLength();
    }

    try {
      long size = 0;

      final Path path = orcSplit.getPath();
      final Path root;
      final int bucket;

      // If the split has a base, extract the base file size, bucket and root path info.
      if (orcSplit.hasBase()) {
        if (orcSplit.isOriginal()) {
          root = path.getParent();
        } else {
          root = path.getParent().getParent();
        }
        size += orcSplit.getLength();
        bucket = AcidUtils.parseBaseBucketFilename(orcSplit.getPath(), conf).getBucket();
      } else {
        root = path;
        bucket = (int) orcSplit.getStart();
      }

      final Path[] deltas = AcidUtils.deserializeDeltas(root, orcSplit.getDeltas());
      // go through each delta directory and add the size of the delta file belonging to the bucket to total split size
      for (Path delta : deltas) {
        final Path deltaFile = AcidUtils.createBucketFile(delta, bucket);
        final FileSystem fs = deltaFile.getFileSystem(conf);
        final FileStatus fileStatus = fs.getFileStatus(deltaFile);
        size += fileStatus.getLen();
      }

      return size;
    } catch (Exception e) {
      logger.warn("Failed to derive the input split size of transactional Hive tables", e);
      // return a non-zero number - we don't want the metadata fetch operation to fail. We could ask the customer to
      // update the stats so that they can be used as part of the planning
      return 1;
    }
  }

  private void buildSplits(final HiveTableXattr.Builder tableExtended, final String dbName, final String tableName,
      final int estimatedRecordSize) throws Exception {
    ReadDefinition metadata = datasetConfig.getReadDefinition();
    final HiveStats metastoreStats = getStatsFromProps(tableProperties);
    setFormat(table, tableExtended);

    boolean allowParquetNative = true;
    HiveStats observedStats = new HiveStats(0,0);

    Stopwatch splitStart = Stopwatch.createStarted();
    final JobConf job = new JobConf(hiveConf);

    if (metadata.getPartitionColumnsList().isEmpty()) {
      Class<? extends InputFormat<?, ?>> inputFormat = getInputFormatClass(job, table, null);
      allowParquetNative = allowParquetNative(allowParquetNative, inputFormat);
      job.setInputFormat(inputFormat);
      final InputFormat<?, ?> format = job.getInputFormat();

      StorageDescriptor sd = table.getSd();
      if(inputPathExists(sd, job)){
        // only generate splits if there is an input path.
        HiveSplitWork hiveSplitWork = new HiveSplitsGenerator(format, sd, estimatedRecordSize, metastoreStats, null, 0).runInner();
        splits.addAll(hiveSplitWork.getSplits());
        observedStats.add(hiveSplitWork.getHiveStats());
      }

      // add a single partition from table properties.
      tableExtended.addAllPartitionProperties(Collections.singletonList(getPartitionProperty(tableExtended, fromProperties(tableProperties))));

      if (format instanceof FileInputFormat) {
        final FileSystemPartitionUpdateKey updateKey = getFSBasedUpdateKey(sd.getLocation(), job, isRecursive(tableProperties), 0);
        if (updateKey != null) {
          metadata.setReadSignature(ByteString.copyFrom(
            HiveReadSignature.newBuilder()
              .setType(HiveReadSignatureType.FILESYSTEM)
              .addAllFsPartitionUpdateKeys(Collections.singletonList(updateKey))
              .build()
              .toByteArray()));
        }
      }
    } else {
      final List<FileSystemPartitionUpdateKey> updateKeys = Lists.newArrayList();
      boolean allFSBasedPartitions = true;
      final List<TimedRunnable<HiveSplitWork>> splitsGenerators = Lists.newArrayList();
      final List<PartitionProp> partitionProps = Lists.newArrayList();
      int partitionId = 0;
      List<Integer> partitionHashes = Lists.newArrayList();

      for(Partition partition : client.getPartitions(dbName, tableName)) {
        partitionHashes.add(getHash(partition));
        final Properties partitionProperties = getPartitionMetadata(partition, table);

        Class<? extends InputFormat<?, ?>> inputFormat = getInputFormatClass(job, table, partition);
        allowParquetNative = allowParquetNative(allowParquetNative, inputFormat);
        job.setInputFormat(inputFormat);

        partitionProps.add(getPartitionProperty(partition, fromProperties(partitionProperties)));

        final InputFormat<?, ?> format = job.getInputFormat();
        final HiveStats totalPartitionStats = getStatsFromProps(partitionProperties);

        StorageDescriptor sd = partition.getSd();
        if (inputPathExists(sd, job)) {
          splitsGenerators.add(new HiveSplitsGenerator(format, sd, estimatedRecordSize, totalPartitionStats, partition, partitionId));
        }
        if (format instanceof FileInputFormat) {
          final FileSystemPartitionUpdateKey updateKey = getFSBasedUpdateKey(sd.getLocation(), job, isRecursive(partitionProperties), partitionId);
          if (updateKey != null) {
            updateKeys.add(updateKey);
          }
        } else {
          allFSBasedPartitions = false;
        }
        ++partitionId;
      }

      Collections.sort(partitionHashes);
      tableExtended.setPartitionHash(Objects.hashCode(partitionHashes));
      // set partition properties in table's xattr
      tableExtended.addAllPartitionProperties(partitionProps);

      if (!splitsGenerators.isEmpty()) {
        final List<HiveSplitWork> hiveSplitWorks = TimedRunnable.run("Get splits for hive table " + tableName, logger, splitsGenerators, HIVE_SPLITS_GENERATOR_PARALLELISM);
        for (HiveSplitWork splitWork : hiveSplitWorks) {
          splits.addAll(splitWork.getSplits());
          observedStats.add(splitWork.getHiveStats());
        }
      }

      // If all partitions had filesystem based partitions then set updatekey
      if (allFSBasedPartitions && !updateKeys.isEmpty()) {
        metadata.setReadSignature(ByteString.copyFrom(
          HiveReadSignature.newBuilder()
            .setType(HiveReadSignatureType.FILESYSTEM)
            .addAllFsPartitionUpdateKeys(updateKeys)
            .build()
            .toByteArray()));
      }
    }

    if(allowParquetNative){
      tableExtended.setReaderType(ReaderType.NATIVE_PARQUET);
    } else {
      tableExtended.setReaderType(ReaderType.BASIC);
    }

    HiveStats actualStats = statsParams.useMetastoreStats() && metastoreStats.isValid() ? metastoreStats : observedStats;
    metadata.setScanStats(new ScanStats()
        .setRecordCount(actualStats.getNumRows())
        .setDiskCost((float) actualStats.getSizeInBytes())
        .setCpuCost((float) actualStats.getSizeInBytes())
        .setType(ScanStatsType.NO_EXACT_ROW_COUNT)
        .setScanFactor(allowParquetNative ? ScanCostFactor.PARQUET.getFactor() : ScanCostFactor.OTHER.getFactor())
        );
    splitStart.stop();
    logger.debug("Computing splits for table {} took {} ms", datasetPath, splitStart.elapsed(TimeUnit.MILLISECONDS));
  }

  /**
   * Find the rowcount based on stats in Hive metastore or estimate using filesize/filetype/recordSize/split size
   * @param statsParams parameters controling the stats calculations
   * @param statsFromMetastore
   * @param sizeRatio Ration of this split contributing to all stats in given <i>statsFromMetastore</i>
   * @param splitSizeInBytes
   * @param format
   * @param estimatedRecordSize
   * @return
   */
  private long findRowCountInSplit(StatsEstimationParameters statsParams, HiveStats statsFromMetastore,
      final double sizeRatio, final long splitSizeInBytes, InputFormat<?, ?> format, final int estimatedRecordSize) {

    final Class<? extends InputFormat<?, ?>> inputFormat =
        format == null ? null : ((Class<? extends InputFormat<?, ?>>) format.getClass());

    double compressionFactor = 1.0;
    if (MapredParquetInputFormat.class.equals(inputFormat)) {
      compressionFactor = 30;
    } else if (OrcInputFormat.class.equals(inputFormat)) {
      compressionFactor = 30f;
    } else if (AvroContainerInputFormat.class.equals(inputFormat)) {
      compressionFactor = 10f;
    } else if (RCFileInputFormat.class.equals(inputFormat)) {
      compressionFactor = 10f;
    }

    final long estimatedRowCount = (long) Math.ceil(splitSizeInBytes * compressionFactor / estimatedRecordSize);

    // Metastore stats are for complete partition. Multiply it by the size ratio of this split
    final long metastoreRowCount = (long) Math.ceil(sizeRatio * statsFromMetastore.getNumRows());

    logger.trace("Hive stats estimation: compression factor {}, recordSize {}, estimated {}, from metastore {}",
        compressionFactor, estimatedRecordSize, estimatedRowCount, metastoreRowCount);

    if (statsParams.useMetastoreStats() && statsFromMetastore.isValid()) {
      return metastoreRowCount;
    }

    // return the maximum of estimate and metastore count
    return Math.max(estimatedRowCount, metastoreRowCount);
  }

  private PartitionProp getPartitionProperty(Partition partition, List<Prop> props) {
    PartitionProp.Builder partitionPropBuilder = PartitionProp.newBuilder();

    if (partition.getSd().getInputFormat() != null) {
      partitionPropBuilder.setInputFormat(partition.getSd().getInputFormat());
    }
    if (partition.getParameters().get(META_TABLE_STORAGE) != null) {
      partitionPropBuilder.setStorageHandler(partition.getParameters().get(META_TABLE_STORAGE));
    }
    if (partition.getSd().getSerdeInfo().getSerializationLib() != null) {
      partitionPropBuilder.setSerializationLib(partition.getSd().getSerdeInfo().getSerializationLib());
    }
    partitionPropBuilder.addAllPartitionProperty(props);
    return partitionPropBuilder.build();
  }

  private PartitionProp getPartitionProperty(HiveTableXattr.Builder tableExtended, List<Prop> props) {
    // set a single partition for a table
    PartitionProp.Builder partitionPropBuilder = PartitionProp.newBuilder();
    if (tableExtended.getInputFormat() != null) {
      partitionPropBuilder.setInputFormat(tableExtended.getInputFormat());
    }
    if (tableExtended.getStorageHandler() != null) {
      partitionPropBuilder.setStorageHandler(tableExtended.getStorageHandler());
    }
    if (tableExtended.getSerializationLib() != null) {
      partitionPropBuilder.setSerializationLib(tableExtended.getSerializationLib());
    }
    partitionPropBuilder.addAllPartitionProperty(props);
    return partitionPropBuilder.build();
  }

  private FileSystemPartitionUpdateKey getFSBasedUpdateKey(String partitionDir, JobConf job, boolean isRecursive, int partitionId) throws IOException {
    final List<FileSystemCachedEntity> cachedEntities = Lists.newArrayList();
    final Path rootLocation = new Path(partitionDir);
    final FileSystemWrapper fs = FileSystemWrapper.get(rootLocation, job);

    if (fs.exists(rootLocation)) {
      final FileStatus rootStatus = fs.getFileStatus(rootLocation);
      if (rootStatus.isDirectory()) {
        cachedEntities.add(FileSystemCachedEntity.newBuilder()
          .setPath(EMPTY_STRING)
          .setLastModificationTime(rootStatus.getModificationTime())
          .setIsDir(true)
          .build());

        List<FileStatus> statuses = isRecursive ? fs.listRecursive(rootLocation, false) : fs.list(rootLocation, false);
        for (FileStatus fileStatus : statuses) {
          final Path filePath = fileStatus.getPath();
          if (fileStatus.isDirectory()) {
            cachedEntities.add(FileSystemCachedEntity.newBuilder()
              .setPath(PathUtils.relativePath(filePath, rootLocation))
              .setLastModificationTime(fileStatus.getModificationTime())
              .setIsDir(true)
              .build());
          } else if (fileStatus.isFile()) {
            cachedEntities.add(FileSystemCachedEntity.newBuilder()
              .setPath(PathUtils.relativePath(filePath, rootLocation))
              .setLastModificationTime(fileStatus.getModificationTime())
              .setIsDir(false)
              .build());
          }
        }
      } else {
        cachedEntities.add(FileSystemCachedEntity.newBuilder()
          .setPath(EMPTY_STRING)
          .setLastModificationTime(rootStatus.getModificationTime())
          .setIsDir(false)
          .build());
      }
      return FileSystemPartitionUpdateKey.newBuilder()
        .setPartitionId(partitionId)
        .setPartitionRootDir(fs.makeQualified(rootLocation).toString())
        .addAllCachedEntities(cachedEntities)
        .build();
    }
    return null;
  }

  private static boolean inputPathExists(StorageDescriptor sd, JobConf job) throws IOException {
    final Path path = new Path(sd.getLocation());
    final FileSystem fs = FileSystemWrapper.get(path, job);

    if (fs.exists(path)) {
      return true;
    }

    return false;
  }

  private static void addInputPath(StorageDescriptor sd, JobConf job) {
    final Path path = new Path(sd.getLocation());
    FileInputFormat.addInputPath(job, path);
  }

  @SuppressWarnings("unchecked")
  public List<Prop> fromProperties(Properties props){
    List<Prop> output = new ArrayList<>();
    for(Entry<Object, Object> eo : props.entrySet()){
      Entry<String, String> e = (Entry<String, String>) (Object) eo;
      output.add(Prop.newBuilder().setKey(e.getKey()).setValue(e.getValue()).build());
    }
    return output;
  }

  public static void setFormat(final Table table, HiveTableXattr.Builder xattr) throws ExecutionSetupException{
    if(table.getSd().getInputFormat() != null){
      xattr.setInputFormat(table.getSd().getInputFormat());
      return;
    }

    if(table.getParameters().get(META_TABLE_STORAGE) != null){
      xattr.setStorageHandler(table.getParameters().get(META_TABLE_STORAGE));
      return;
    }
  }

  private static List<PartitionValue> getPartitions(Table table, Partition partition) {
    if(partition == null){
      return Collections.emptyList();
    }

    final List<String> partitionValues = partition.getValues();
    final List<PartitionValue> output = Lists.newArrayList();
    final List<FieldSchema> partitionKeys = table.getPartitionKeys();
    for(int i =0; i < partitionKeys.size(); i++){
      PartitionValue value = getPartitionValue(partitionKeys.get(i), partitionValues.get(i));
      if(value != null){
        output.add(value);
      }
    }
    return output;
  }

  private static PartitionValue getPartitionValue(FieldSchema partitionCol, String value) {
    final TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(partitionCol.getType());
    PartitionValue out = new PartitionValue();
    out.setColumn(partitionCol.getName());

    if("__HIVE_DEFAULT_PARTITION__".equals(value)){
      return out;
    }

    switch (typeInfo.getCategory()) {
      case PRIMITIVE:
        final PrimitiveTypeInfo primitiveTypeInfo = (PrimitiveTypeInfo) typeInfo;
        switch (((PrimitiveTypeInfo) typeInfo).getPrimitiveCategory()) {
          case BINARY:
            return out.setBinaryValue(ByteString.copyFrom(value.getBytes()));
          case BOOLEAN:
            return out.setBitValue(Boolean.parseBoolean(value));
          case DOUBLE:
            return out.setDoubleValue(Double.parseDouble(value));
          case FLOAT:
            return out.setFloatValue(Float.parseFloat(value));
          case BYTE:
          case SHORT:
          case INT:
            return out.setIntValue(Integer.parseInt(value));
          case LONG:
            return out.setLongValue(Long.parseLong(value));
          case STRING:
          case VARCHAR:
            return out.setStringValue(value);
          case CHAR:
            return out.setStringValue(value.trim());
          case TIMESTAMP:
            return out.setLongValue(DateTimes.toMillisFromJdbcTimestamp(value));
          case DATE:
            return out.setLongValue(DateTimes.toMillisFromJdbcDate(value));
          case DECIMAL:
            DecimalTypeInfo decimalTypeInfo = (DecimalTypeInfo) typeInfo;
            if(decimalTypeInfo.getPrecision() > 38){
              throw UserException.unsupportedError()
                .message("Dremio only supports decimals up to 38 digits in precision. This Hive table has a partition value with scale of %d digits.", decimalTypeInfo.getPrecision())
                .build(logger);
            }
            HiveDecimal decimal = HiveDecimalUtils.enforcePrecisionScale(HiveDecimal.create(value), decimalTypeInfo);
            final BigDecimal original = decimal.bigDecimalValue();
            // we can't just use unscaledValue() since BigDecimal doesn't store trailing zeroes and we need to ensure decoding includes the correct scale.
            final BigInteger unscaled = original.movePointRight(decimalTypeInfo.scale()).unscaledValue();
            return out.setBinaryValue(ByteString.copyFrom(DecimalTools.signExtend16(unscaled.toByteArray())));
          default:
            HiveUtilities.throwUnsupportedHiveDataTypeError(((PrimitiveTypeInfo) typeInfo).getPrimitiveCategory().toString());
        }
      default:
        HiveUtilities.throwUnsupportedHiveDataTypeError(typeInfo.getCategory().toString());
    }

    return null; // unreachable
  }

  /**
   * Wrapper around {@link MetaStoreUtils#getPartitionMetadata(Partition, Table)} which also adds parameters from table
   * to properties returned by {@link MetaStoreUtils#getPartitionMetadata(Partition, Table)}.
   *
   * @param partition the source of partition level parameters
   * @param table     the source of table level parameters
   * @return properties
   */
  public static Properties getPartitionMetadata(final Partition partition, final Table table) {
    final Properties properties = MetaStoreUtils.getPartitionMetadata(partition, table);

    // SerDe expects properties from Table, but above call doesn't add Table properties.
    // Include Table properties in final list in order to not to break SerDes that depend on
    // Table properties. For example AvroSerDe gets the schema from properties (passed as second argument)
    for (Map.Entry<String, String> entry : table.getParameters().entrySet()) {
      if (entry.getKey() != null && entry.getKey() != null) {
        properties.put(entry.getKey(), entry.getValue());
      }
    }

    return properties;
  }


  /**
   * Utility method which adds give configs to {@link JobConf} object.
   *
   * @param job {@link JobConf} instance.
   * @param properties New config properties
   */
  public static void addConfToJob(final JobConf job, final Properties properties) {
    for (Object obj : properties.keySet()) {
      job.set((String) obj, (String) properties.get(obj));
    }

    HiveUtilities.addACIDPropertiesIfNeeded(job);
  }

  public static Class<? extends InputFormat<?, ?>> getInputFormatClass(final JobConf job, final Table table, final Partition partition) throws Exception {
    if(partition != null){
      if(partition.getSd().getInputFormat() != null){
        return (Class<? extends InputFormat<?, ?>>) Class.forName(partition.getSd().getInputFormat());
      }

      if(partition.getParameters().get(META_TABLE_STORAGE) != null){
        final HiveStorageHandler storageHandler = HiveUtils.getStorageHandler(job, partition.getParameters().get(META_TABLE_STORAGE));
        return (Class<? extends InputFormat<?, ?>>) storageHandler.getInputFormatClass();
      }
    }

    if(table.getSd().getInputFormat() != null){
      return (Class<? extends InputFormat<?, ?>>) Class.forName(table.getSd().getInputFormat());
    }

    if(table.getParameters().get(META_TABLE_STORAGE) != null){
      final HiveStorageHandler storageHandler = HiveUtils.getStorageHandler(job, table.getParameters().get(META_TABLE_STORAGE));
      return (Class<? extends InputFormat<?, ?>>) storageHandler.getInputFormatClass();
    }

    throw new ExecutionSetupException("Unable to get Hive table InputFormat class. There is neither " +
        "InputFormat class explicitly specified nor a StorageHandler class provided.");
  }

  @Override
  public DatasetType getType() {
    return DatasetType.PHYSICAL_DATASET;
  }

  @Override
  public boolean isSaveable() {
    return true;
  }

  static int getHash(Table table) {
    return Objects.hashCode(
      table.getTableType(),
      table.getParameters(),
      table.getPartitionKeys(),
      table.getSd(),
      table.getViewExpandedText(),
      table.getViewOriginalText());
  }

  static int getHash(Partition partition) {
    return Objects.hashCode(
      partition.getSd(),
      partition.getParameters(),
      partition.getValues());
  }

  private int leafFieldCounter(int leafCounter) {
    leafCounter++;
    if (leafCounter > maxMetadataLeafColumns) {
      throw new ColumnCountTooLargeException(
          String.format("Using datasets with more than %d columns is currently disabled.", maxMetadataLeafColumns));
    }
    return leafCounter;
  }
}