package io.openlineage.spark.agent;

import static io.openlineage.spark.agent.ArgumentParser.*;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerApplicationEnd;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;

/**
 * Executes spark-event -> run-event translation and emission of run event in its own internal
 * threadpool asynchronously for better isolation of lineage listener from other listeners and spark
 * itself. In order to not disturb the ordering of run-events significantly, it does wait for the
 * completion of the asynchronous tasks for up to 1 second (configurable) but then moves on. This
 * unblocks other listeners sharing the same listener queue in spark.
 *
 * <p>Only application start and end events are processed emitted synchronously, because typically
 * they are lightweight events.
 *
 * <p>It also gives up processing all pending spark events when the application end is observed.
 * This is to let the spark driver process exit at the cost of losing lineage run events,
 * particularly when the lineage connector is really backlogged.
 */
@Slf4j
public class OpenLineageSparkAsyncListener extends OpenLineageSparkListener {
  private SparkConf sparkConf;
  private BlockingQueue<Runnable> eventQueue;
  private ExecutorService eventProcessingExecutor;
  private long timeoutSeconds = 1L;
  private long shutdownTimeoutSeconds = 60L;

  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong timedOut = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  public OpenLineageSparkAsyncListener(SparkConf sparkConf) {
    log.info("Initializing OpenLineageAsyncListener with sparkConf = " + sparkConf.toDebugString());
    Objects.requireNonNull(
        sparkConf,
        "Failing OpenLineageAsyncListener initialization as Spark configuration is null");
    this.sparkConf = sparkConf;
    final int queueSize = sparkConf.getInt(SPARK_CONF_ASYNC_QUEUESIZE, 1000);
    final int threadCount = sparkConf.getInt(SPARK_CONF_ASYNC_THREADCOUNT, 2);
    timeoutSeconds = sparkConf.getLong(SPARK_CONF_ASYNC_TIMEOUT, timeoutSeconds);
    shutdownTimeoutSeconds =
        sparkConf.getLong(SPARK_CONF_ASYNC_SHUTDOWN_WAIT, shutdownTimeoutSeconds);
    eventQueue = new ArrayBlockingQueue<>(queueSize);
    final ThreadFactory factory =
        new ThreadFactoryBuilder().setNameFormat("openlineage-listener-%d").build();
    eventProcessingExecutor =
        new ThreadPoolExecutor(
            threadCount, threadCount, 60L, TimeUnit.SECONDS, eventQueue, factory);
    log.info(
        "Initialized async listener with threads={}, queueSize={}, timeout={} shutdownWait={}",
        queueSize,
        threadCount,
        timeoutSeconds,
        shutdownTimeoutSeconds);
  }

  private int pendingTasks() {
    return eventQueue == null ? 0 : eventQueue.size();
  }

  // Constructor for testing
  public OpenLineageSparkAsyncListener(SparkConf conf, ExecutorService executorService) {
    this.eventProcessingExecutor = executorService;
    this.sparkConf = conf;
  }

  @Override
  public final void onApplicationEnd(SparkListenerApplicationEnd applicationEnd) {
    try {
      // First just shutdown the executor. It does NOT cancel already submitted tasks, just won't
      // accept new tasks.
      eventProcessingExecutor.shutdown();
      // Wait for a shutdownWait seconds for the pending tasks to be executed. If they are not
      // executed by that time,
      // force a shutdown where the pending tasks are also abandoned.
      eventProcessingExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS);
      // Force shutdown, canceling pending tasks. This will result in loss of events.
      List<Runnable> canceledTasks = eventProcessingExecutor.shutdownNow();
      dropped.addAndGet(canceledTasks.size());
    } catch (Exception e) {
      log.error("Unable to shutdown pending event processing tasks", e);
    }
    // Once pending tasks are complete/conceled, process this end event synchronously
    try {
      super.onApplicationEnd(applicationEnd);
    } finally {
      log.info(
          "Openlineage async stats: dropped={}, timeout={}, queueDepth={}, failed={}",
          dropped.get(),
          timedOut.get(),
          pendingTasks(),
          failed.get());
    }
  }

  @Override
  public final void onOtherEvent(SparkListenerEvent event) {
    try {
      eventProcessingExecutor
          .submit(() -> super.onOtherEvent(event))
          .get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (RejectedExecutionException re) {
      dropped.incrementAndGet();
    } catch (TimeoutException e) {
      timedOut.incrementAndGet();
    } catch (Exception e) {
      failed.incrementAndGet();
    } finally {
      log.info(
          "Openlineage async stats: dropped={}, timeout={}, queueDepth={}, failed={}",
          dropped.get(),
          timedOut.get(),
          pendingTasks(),
          failed.get());
    }
  }

  @Override
  public final void onJobStart(SparkListenerJobStart jobStart) {
    try {
      eventProcessingExecutor
          .submit(() -> super.onJobStart(jobStart))
          .get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (RejectedExecutionException re) {
      dropped.incrementAndGet();
    } catch (TimeoutException e) {
      timedOut.incrementAndGet();
    } catch (Exception e) {
      failed.incrementAndGet();
    } finally {
      log.info(
          "Openlineage async stats: dropped={}, timeout={}, queueDepth={}, failed={}",
          dropped.get(),
          timedOut.get(),
          pendingTasks(),
          failed.get());
    }
  }

  @Override
  public final void onJobEnd(SparkListenerJobEnd jobEnd) {
    try {
      eventProcessingExecutor
          .submit(() -> super.onJobEnd(jobEnd))
          .get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (RejectedExecutionException re) {
      dropped.incrementAndGet();
    } catch (TimeoutException e) {
    } catch (Exception e) {
      failed.incrementAndGet();
    } finally {
      log.info(
          "Openlineage async stats: dropped={}, timeout={}, queueDepth={}, failed={}",
          dropped.get(),
          timedOut.get(),
          pendingTasks(),
          failed.get());
    }
  }

  public long getDroppedCount() {
    return dropped.get();
  }

  public long getFailedCount() {
    return failed.get();
  }

  public long getTimedoutCount() {
    return timedOut.get();
  }

  public int getPendingTasks() {
    return eventQueue.size();
  }
}
