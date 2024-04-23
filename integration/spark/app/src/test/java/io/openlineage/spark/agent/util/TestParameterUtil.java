package io.openlineage.spark.agent.util;

import com.google.common.util.concurrent.MoreExecutors;
import io.openlineage.spark.agent.OpenLineageSparkAsyncListener;
import io.openlineage.spark.agent.OpenLineageSparkListener;
import io.openlineage.spark.agent.lifecycle.ContextFactory;
import java.util.concurrent.ExecutorService;
import org.apache.spark.SparkConf;

public class TestParameterUtil {
  public static OpenLineageSparkListener createListener(
      SparkListenerType listenerType, SparkConf conf, ContextFactory contextFactory) {
    switch (listenerType) {
      case ASYNC:
        ExecutorService executorService = MoreExecutors.newDirectExecutorService();
        OpenLineageSparkAsyncListener.init(contextFactory);
        return new OpenLineageSparkAsyncListener(conf, executorService);
      default:
        OpenLineageSparkListener.init(contextFactory);
        return new OpenLineageSparkListener();
    }
  }
}
