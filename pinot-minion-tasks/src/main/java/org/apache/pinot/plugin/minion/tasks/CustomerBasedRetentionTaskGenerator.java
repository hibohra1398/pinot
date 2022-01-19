package org.apache.pinot.plugin.minion.tasks;

import static org.apache.pinot.common.minion.MinionTaskMetadataUtils.fetchMinionTaskMetadataZNRecord;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionConstants.CUSTOMER_RETENTION_CONFIG;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionConstants.RETENTION_PERIOD_KEY;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionConstants.TASK_TYPE;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionConstants.WINDOW_END_MS_KEY;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionConstants.WINDOW_START_MS_KEY;
import static org.apache.pinot.plugin.minion.tasks.CustomerBasedRetentionTaskUtils.setCustomerBasedRetentionTaskMetadata;

import com.google.common.base.Preconditions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.helix.ZNRecord;
import org.apache.helix.store.HelixPropertyStore;
import org.apache.helix.task.TaskState;
import org.apache.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import org.apache.pinot.controller.helix.core.minion.ClusterInfoAccessor;
import org.apache.pinot.controller.helix.core.minion.generator.PinotTaskGenerator;
import org.apache.pinot.controller.helix.core.minion.generator.TaskGeneratorUtils;
import org.apache.pinot.core.common.MinionConstants;
import org.apache.pinot.core.minion.PinotTaskConfig;
import org.apache.pinot.spi.annotations.minion.TaskGenerator;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableTaskConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.utils.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGenerator
public class CustomerBasedRetentionTaskGenerator implements PinotTaskGenerator{

  private static final Logger LOGGER = LoggerFactory.getLogger(CustomerBasedRetentionTaskGenerator.class);
  private static final String COLUMNS_TO_CONVERT_KEY = "columnsToConvert";
  private static final int MAX_SEGMENTS_PER_TASK = 32;

  private ClusterInfoAccessor _clusterInfoAccessor;
  private HelixPropertyStore<ZNRecord> propertyStore;

  @Override
  public void init(ClusterInfoAccessor clusterInfoAccessor) {
    _clusterInfoAccessor = clusterInfoAccessor;
  }

  @Override
  public String getTaskType() {
    return TASK_TYPE;
  }

  @Override
  public List<PinotTaskConfig> generateTasks(List<TableConfig> tableConfigs) {
    List<PinotTaskConfig> pinotTaskConfigs = new ArrayList<>();

    for (TableConfig tableConfig : tableConfigs) {
      String offlineTableName = tableConfig.getTableName();

      LOGGER.info("Start generating task configs for table: {} for task: {}", offlineTableName, TASK_TYPE);

      // Only generate tasks for OFFLINE tables
      if (tableConfig.getTableType() != TableType.OFFLINE) {
        LOGGER.warn("Skip generating task: {} for non-OFFLINE table: {}", TASK_TYPE, offlineTableName);
        continue;
      }

      // Only schedule 1 job of this type (this optimisation may be moved downstream later on)
      Map<String, TaskState> incompleteTasks =
          TaskGeneratorUtils.getIncompleteTasks(TASK_TYPE, offlineTableName, _clusterInfoAccessor);
      if (!incompleteTasks.isEmpty()) {
        LOGGER
            .warn("Found incomplete tasks: {} for same table: {}. Skipping task generation.", incompleteTasks.keySet(),
                offlineTableName);
        continue;
      }

      TableTaskConfig tableTaskConfig = tableConfig.getTaskConfig();
      Preconditions.checkNotNull(tableTaskConfig);
      Map<String, String> taskConfigs = tableTaskConfig.getConfigsForTaskType(TASK_TYPE);
      Preconditions.checkNotNull(taskConfigs, "Task config shouldn't be null for Table: {}", offlineTableName);

      // Get customer retention config
      Map<String ,String> customerRetentionConfigMap = getCustomerRetentionConfig();
      String customerRetentionConfigMapString = customerRetentionConfigMap.keySet().stream()
          .map(key -> key + "=" + customerRetentionConfigMap.get(key))
          .collect(Collectors.joining(", ", "{", "}"));
      SortedSet<String> sortedDistinctRetentionPeriods = getSortedDistinctRetentionPeriods(customerRetentionConfigMap);

      // Generate one task per retention period. This is because we have to update watermarks based on retention period.
      for (String retentionPeriod : sortedDistinctRetentionPeriods) {

        long retentionPeriodMs = TimeUtils.convertPeriodToMillis(retentionPeriod);

        // Get watermark from OfflineSegmentsMetadata ZNode. WindowStart = watermark. WindowEnd = windowStart + bucket.
        long windowStartMs = 0;
        try {
          windowStartMs = getWatermarkMs(offlineTableName, retentionPeriodMs, sortedDistinctRetentionPeriods);
        } catch (NoSuchFieldException | IllegalAccessException e) {
          e.printStackTrace();
        }
        long windowEndMs = windowStartMs + retentionPeriodMs; // next watermark

        List<String> segmentNames = new ArrayList<>();
        List<String> downloadURLs = new ArrayList<>();
        List<String> originalSegmentCRCs = new ArrayList<>();

        int numSegments = 0;
        List<OfflineSegmentZKMetadata> sortedOfflineSegmentZKMetadataList = getSortedOfflineSegmentZKMetadataList(offlineTableName);
        for (OfflineSegmentZKMetadata offlineSegmentZKMetadata : sortedOfflineSegmentZKMetadataList) {

          // Generate up to maxSegmentsPerTask per retention period
          if (numSegments > MAX_SEGMENTS_PER_TASK) {
            break;
          }

          // Only submit segments that have not been converted
          Map<String, String> customMap = offlineSegmentZKMetadata.getCustomMap();
          if (customMap == null || !customMap.containsKey(COLUMNS_TO_CONVERT_KEY + MinionConstants.TASK_TIME_SUFFIX)) {
            segmentNames.add(offlineSegmentZKMetadata.getSegmentName());
            downloadURLs.add(offlineSegmentZKMetadata.getDownloadUrl());
            originalSegmentCRCs.add(String.valueOf(offlineSegmentZKMetadata.getCrc()));
            numSegments++;
          }
        }

        if (!segmentNames.isEmpty()) {
          Map<String, String> configs = new HashMap<>();

          configs.put(MinionConstants.TABLE_NAME_KEY, offlineTableName);
          configs.put(MinionConstants.SEGMENT_NAME_KEY, StringUtils.join(segmentNames, ","));
          configs.put(MinionConstants.DOWNLOAD_URL_KEY, StringUtils.join(downloadURLs, MinionConstants.URL_SEPARATOR));
          configs.put(MinionConstants.UPLOAD_URL_KEY, _clusterInfoAccessor.getVipUrl() + "/segments");
          configs.put(MinionConstants.ORIGINAL_SEGMENT_CRC_KEY, StringUtils.join(originalSegmentCRCs, ","));

          // Customer config
          configs.put(CUSTOMER_RETENTION_CONFIG, customerRetentionConfigMapString);
          configs.put(RETENTION_PERIOD_KEY, retentionPeriod);

          // Execution window
          configs.put(WINDOW_START_MS_KEY, String.valueOf(windowStartMs));
          configs.put(WINDOW_END_MS_KEY, String.valueOf(windowEndMs));

          pinotTaskConfigs.add(new PinotTaskConfig(TASK_TYPE, configs));
        }
      }
      LOGGER.info("Finished generating task configs for table: {} for task: {}", offlineTableName, TASK_TYPE);
    }
    return pinotTaskConfigs;
  }

  /**
   * Get the watermark from the CustomerBasedRetentionTaskMetadata ZNode.
   * If the ZNode is null, computes the watermark using the min start time of all
   * segments from segment metadata + the retention period.
   */
  private long getWatermarkMs(String offlineTableName, long bucketMs, SortedSet<String> sortedDistinctRetentionPeriods)
      throws NoSuchFieldException, IllegalAccessException {
    setPropertyStore();
    List<OfflineSegmentZKMetadata> offlineSegmentZKMetadataList =
        _clusterInfoAccessor.getOfflineSegmentsMetadata(offlineTableName);
    CustomerBasedRetentionTaskMetadata customerBasedRetentionTaskMetadata =
        getCustomerBasedRetentionTaskMetadata(offlineTableName);

    // No ZNode exists.
    if (customerBasedRetentionTaskMetadata == null) {

      // Find the smallest time from all segments
      long minStartTimeMs = Long.MAX_VALUE;
      for (OfflineSegmentZKMetadata offlineSegmentZKMetadata : offlineSegmentZKMetadataList) {
        minStartTimeMs = Math.min(minStartTimeMs, offlineSegmentZKMetadata.getStartTimeMs());
      }
      Preconditions.checkState(minStartTimeMs != Long.MAX_VALUE);

      // Compute the watermark map
      Map<String,String> watermarkMap = new HashMap<>();
      for (String retentionPeriod : sortedDistinctRetentionPeriods) {

        // Task will start from the earliest time + retention period
        minStartTimeMs += bucketMs;

        // Round off according to the bucket. This ensures we align the offline segments to proper time boundaries
        // For example, if start time millis is 20200813T12:34:59, we want to create the first segment for window [20200813, 20200814)
        long watermarkMs = (minStartTimeMs / bucketMs) * bucketMs;

        watermarkMap.put(retentionPeriod, Long.toString(watermarkMs));
      }

      // Create CustomerBasedRetentionTaskMetadata ZNode using watermark calculated above
      customerBasedRetentionTaskMetadata = new CustomerBasedRetentionTaskMetadata(offlineTableName, watermarkMap);
      setCustomerBasedRetentionTaskMetadata(customerBasedRetentionTaskMetadata, propertyStore, -1);
    }

    String waterMark = customerBasedRetentionTaskMetadata.getWatermarkMsMap().get(bucketMs);
    return Long.parseLong(waterMark);
  }

  private Map<String,String> getCustomerRetentionConfig(){
    //todo: add code here
    Map<String,String> customerRetentionConfig = new HashMap<>();
    return customerRetentionConfig;
  }

  private SortedSet<String> getSortedDistinctRetentionPeriods(Map<String,String> customerRetentionConfigMap){
    Set<String> distinctRetentionPeriodsSet = new HashSet<>(customerRetentionConfigMap.values());
    return new TreeSet<>(distinctRetentionPeriodsSet);
  }

  private List<OfflineSegmentZKMetadata> getSortedOfflineSegmentZKMetadataList(String offlineTableName){
    Comparator<OfflineSegmentZKMetadata> compareByStartTime =
        (OfflineSegmentZKMetadata o1, OfflineSegmentZKMetadata o2) -> (int) (o1.getStartTimeMs()-o2.getStartTimeMs());
    List<OfflineSegmentZKMetadata> offlineSegmentZKMetadataList = _clusterInfoAccessor.getOfflineSegmentsMetadata(offlineTableName);
    offlineSegmentZKMetadataList.sort(compareByStartTime);
    return offlineSegmentZKMetadataList;
  }

  private void setPropertyStore()
      throws NoSuchFieldException, IllegalAccessException {
    Field pinotHelixResourceManagerField = ClusterInfoAccessor.class.getDeclaredField("_pinotHelixResourceManager");
    pinotHelixResourceManagerField.setAccessible(true);
    propertyStore = (HelixPropertyStore<ZNRecord>) pinotHelixResourceManagerField.get(_clusterInfoAccessor);
  }

  private CustomerBasedRetentionTaskMetadata getCustomerBasedRetentionTaskMetadata(String offlineTableName){
    ZNRecord znRecord = fetchMinionTaskMetadataZNRecord(propertyStore, TASK_TYPE, offlineTableName);
    return znRecord != null ? CustomerBasedRetentionTaskMetadata.fromZNRecord(znRecord) : null;
  }
}