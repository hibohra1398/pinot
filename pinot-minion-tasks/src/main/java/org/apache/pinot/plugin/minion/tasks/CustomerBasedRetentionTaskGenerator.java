package org.apache.pinot.plugin.minion.tasks;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
  private static final String TASK_TYPE = "customerBasedRetentionTask";
  public static final String BUCKET_TIME_PERIOD_KEY = "bucketTimePeriod";
  private static final String DEFAULT_BUCKET_PERIOD = "1d";
  private static final String CUSTOMER_RETENTION_CONFIG = "customerRetentionConfig";
  public static final String WINDOW_START_MS_KEY = "windowStartMs";
  public static final String WINDOW_END_MS_KEY = "windowEndMs";
  public static final int TABLE_MAX_NUM_TASKS = 1000; // vary accordingly

  private ClusterInfoAccessor _clusterInfoAccessor;

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

      if (tableConfig.getTableType() != TableType.OFFLINE) {
        LOGGER.warn("Skip generating task: {} for non-OFFLINE table: {}", TASK_TYPE, offlineTableName);
        continue;
      }

      LOGGER.info("Start generating task configs for table: {} for task: {}", offlineTableName, TASK_TYPE);

      // Only schedule 1 task of this type, per table
      Map<String, TaskState> incompleteTasks =
          TaskGeneratorUtils.getIncompleteTasks(TASK_TYPE, offlineTableName, _clusterInfoAccessor);
      if (!incompleteTasks.isEmpty()) {
        LOGGER
            .warn("Found incomplete tasks: {} for same table: {}. Skipping task generation.", incompleteTasks.keySet(),
                offlineTableName);
        continue;
      }

      TableTaskConfig tableTaskConfig = tableConfig.getTaskConfig();
      Preconditions.checkState(tableTaskConfig != null);
      Map<String, String> taskConfigs = tableTaskConfig.getConfigsForTaskType(TASK_TYPE);
      Preconditions.checkState(taskConfigs != null, "Task config shouldn't be null for table: {}", offlineTableName);

      // Get the bucket size
      String bucketTimePeriod =
          taskConfigs.getOrDefault(BUCKET_TIME_PERIOD_KEY, DEFAULT_BUCKET_PERIOD);
      long bucketMs = TimeUtils.convertPeriodToMillis(bucketTimePeriod);

      // Get watermark from OfflineSegmentsMetadata ZNode. WindowStart = watermark. WindowEnd = windowStart + bucket.
      long windowStartMs = getWatermarkMs(offlineTableName, bucketMs);
      long windowEndMs = windowStartMs + bucketMs;

      // Get max number of tasks for this table
      int tableMaxNumTasks = getTableMaxNumTasks(taskConfigs);

      // Get customer retention config
      Map<String ,String> customerRetentionConfigMap = getCustomerRetentionConfig();
      String customerRetentionConfigMapString = customerRetentionConfigMap.keySet().stream()
          .map(key -> key + "=" + customerRetentionConfigMap.get(key))
          .collect(Collectors.joining(", ", "{", "}"));

      // Generate tasks
      int tableNumTasks = 0;
      for (OfflineSegmentZKMetadata offlineSegmentZKMetadata : _clusterInfoAccessor.getOfflineSegmentsMetadata(offlineTableName)) {

        // Generate up to tableMaxNumTasks tasks each time for each table
        if (tableNumTasks == tableMaxNumTasks) {
          break;
        }

        // Only submit segments that have not been converted
        Map<String, String> customMap = offlineSegmentZKMetadata.getCustomMap();
        if (customMap == null || !customMap.containsKey(
            MinionConstants.ConvertToRawIndexTask.COLUMNS_TO_CONVERT_KEY + MinionConstants.TASK_TIME_SUFFIX)) {
          Map<String, String> configs = new HashMap<>();
          configs.put(MinionConstants.TABLE_NAME_KEY, offlineTableName);
          configs.put(MinionConstants.SEGMENT_NAME_KEY, offlineSegmentZKMetadata.getSegmentName());
          configs.put(MinionConstants.DOWNLOAD_URL_KEY, offlineSegmentZKMetadata.getDownloadUrl());
          configs.put(MinionConstants.UPLOAD_URL_KEY, _clusterInfoAccessor.getVipUrl() + "/segments");
          configs.put(MinionConstants.ORIGINAL_SEGMENT_CRC_KEY, String.valueOf(offlineSegmentZKMetadata.getCrc()));
          configs.put(CUSTOMER_RETENTION_CONFIG, customerRetentionConfigMapString);
          configs.put(WINDOW_START_MS_KEY, String.valueOf(windowStartMs));
          configs.put(WINDOW_END_MS_KEY, String.valueOf(windowEndMs));
          pinotTaskConfigs.add(new PinotTaskConfig(TASK_TYPE, configs));
          tableNumTasks++;
        }
      }
      LOGGER.info("Finished generating task configs for table: {} for task: {}", offlineTableName, TASK_TYPE);
    }
    return pinotTaskConfigs;
  }

  private long getWatermarkMs(String offlineTableName, long bucketMs){
    // add code here
    return 0;
  }

  private Map<String,String> getCustomerRetentionConfig(){
    // add code here
    Map<String,String> customerRetentionConfig = new HashMap<>();
    return customerRetentionConfig;
  }

  private int getTableMaxNumTasks(Map<String,String> taskConfigs){
    int tableMaxNumTasks;
    String tableMaxNumTasksConfig = taskConfigs.get(MinionConstants.TABLE_MAX_NUM_TASKS_KEY);
    if (tableMaxNumTasksConfig != null) {
      try {
        tableMaxNumTasks = Integer.parseInt(tableMaxNumTasksConfig);
      } catch (Exception e) {
        tableMaxNumTasks = TABLE_MAX_NUM_TASKS;
      }
    } else {
      tableMaxNumTasks = TABLE_MAX_NUM_TASKS;
    }
    return tableMaxNumTasks;
  }
}
