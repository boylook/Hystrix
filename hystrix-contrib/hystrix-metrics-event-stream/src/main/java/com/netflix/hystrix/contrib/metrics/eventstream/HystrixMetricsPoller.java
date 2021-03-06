/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.contrib.metrics.eventstream;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCollapserMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCircuitBreaker;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;

/**
 * Polls Hystrix metrics and output JSON strings for each metric to a MetricsPollerListener.
 * <p>
 * Polling can be stopped/started. Use shutdown() to permanently shutdown the poller.
 */
public class HystrixMetricsPoller {

    static final Logger logger = LoggerFactory.getLogger(HystrixMetricsPoller.class);
    private final ScheduledExecutorService executor;
    private final int delay;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> scheduledTask = null;
    private final MetricsAsJsonPollerListener listener;

    /**
     * Allocate resources to begin polling.
     * <p>
     * Use <code>start</code> to begin polling.
     * <p>
     * Use <code>shutdown</code> to cleanup resources and stop polling.
     * <p>
     * Use <code>pause</code> to temporarily stop polling that can be restarted again with <code>start</code>.
     * 
     * @param listener for callbacks
     * @param delay
     */
    public HystrixMetricsPoller(MetricsAsJsonPollerListener listener, int delay) {
        this.listener = listener;
        executor = new ScheduledThreadPoolExecutor(1, new MetricsPollerThreadFactory());
        this.delay = delay;
    }

    /**
     * Start polling.
     */
    public synchronized void start() {
        // use compareAndSet to make sure it starts only once and when not running
        if (running.compareAndSet(false, true)) {
            logger.info("Starting HystrixMetricsPoller");
            try {
                scheduledTask = executor.scheduleWithFixedDelay(new MetricsPoller(listener), 0, delay, TimeUnit.MILLISECONDS);
            } catch (Throwable ex) {
                logger.error("Exception while creating the MetricsPoller task");
                ex.printStackTrace();
                running.set(false);
            }
        }
    }

    /**
     * Pause (stop) polling. Polling can be started again with <code>start</code> as long as <code>shutdown</code> is not called.
     */
    public synchronized void pause() {
        // use compareAndSet to make sure it stops only once and when running
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping the HystrixMetricsPoller");
            scheduledTask.cancel(true);
        } else {
            logger.debug("Attempted to pause a stopped poller");
        }
    }

    /**
     * Stops polling and shuts down the ExecutorService.
     * <p>
     * This instance can no longer be used after calling shutdown.
     */
    public synchronized void shutdown() {
        pause();
        executor.shutdown();
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Used to protect against leaking ExecutorServices and threads if this class is abandoned for GC without shutting down.
     */
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        protected void finalize() throws Throwable {
            if (!executor.isShutdown()) {
                logger.warn(HystrixMetricsPoller.class.getSimpleName() + " was not shutdown. Caught in Finalize Guardian and shutting down.");
                try {
                    shutdown();
                } catch (Exception e) {
                    logger.error("Failed to shutdown " + HystrixMetricsPoller.class.getSimpleName(), e);
                }
            }
        };
    };

    public static interface MetricsAsJsonPollerListener {
        public void handleJsonMetric(String json);
    }

    private class MetricsPoller implements Runnable {

        private final MetricsAsJsonPollerListener listener;
        private final JsonFactory jsonFactory = new JsonFactory();

        public MetricsPoller(MetricsAsJsonPollerListener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            try {
                for (HystrixCommandMetrics commandMetrics : HystrixCommandMetrics.getInstances()) {
                    String jsonString = getCommandJson(commandMetrics);
                    listener.handleJsonMetric(jsonString);
                }

                for (HystrixThreadPoolMetrics threadPoolMetrics : HystrixThreadPoolMetrics.getInstances()) {
                    if (hasExecutedCommandsOnThread(threadPoolMetrics)) {
                        String jsonString = getThreadPoolJson(threadPoolMetrics);
                        listener.handleJsonMetric(jsonString);
                    }
                }

                for (HystrixCollapserMetrics collapserMetrics : HystrixCollapserMetrics.getInstances()) {
                    String jsonString = getCollapserJson(collapserMetrics);
                    listener.handleJsonMetric(jsonString);
                }

            } catch (Exception e) {
                logger.warn("Failed to output metrics as JSON", e);
                // shutdown
                pause();
                return;
            }
        }

        private String getCommandJson(HystrixCommandMetrics commandMetrics) throws IOException {
            HystrixCommandKey key = commandMetrics.getCommandKey();
            HystrixCircuitBreaker circuitBreaker = HystrixCircuitBreaker.Factory.getInstance(key);

            StringWriter jsonString = new StringWriter();
            JsonGenerator json = jsonFactory.createGenerator(jsonString);

            json.writeStartObject();
            json.writeStringField("type", "HystrixCommand");
            json.writeStringField("name", key.name());
            json.writeStringField("group", commandMetrics.getCommandGroup().name());
            json.writeNumberField("currentTime", System.currentTimeMillis());

            // circuit breaker
            if (circuitBreaker == null) {
                // circuit breaker is disabled and thus never open
                json.writeBooleanField("isCircuitBreakerOpen", false);
            } else {
                json.writeBooleanField("isCircuitBreakerOpen", circuitBreaker.isOpen());
            }
            HealthCounts healthCounts = commandMetrics.getHealthCounts();
            json.writeNumberField("errorPercentage", healthCounts.getErrorPercentage());
            json.writeNumberField("errorCount", healthCounts.getErrorCount());
            json.writeNumberField("requestCount", healthCounts.getTotalRequests());

            // rolling counters
            json.writeNumberField("rollingCountBadRequests", commandMetrics.getRollingCount(HystrixRollingNumberEvent.BAD_REQUEST));
            json.writeNumberField("rollingCountCollapsedRequests", commandMetrics.getRollingCount(HystrixRollingNumberEvent.COLLAPSED));
            json.writeNumberField("rollingCountEmit", commandMetrics.getRollingCount(HystrixRollingNumberEvent.EMIT));
            json.writeNumberField("rollingCountExceptionsThrown", commandMetrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            json.writeNumberField("rollingCountFailure", commandMetrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            json.writeNumberField("rollingCountEmit", commandMetrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_EMIT));
            json.writeNumberField("rollingCountFallbackFailure", commandMetrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            json.writeNumberField("rollingCountFallbackRejection", commandMetrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            json.writeNumberField("rollingCountFallbackSuccess", commandMetrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            json.writeNumberField("rollingCountResponsesFromCache", commandMetrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));
            json.writeNumberField("rollingCountSemaphoreRejected", commandMetrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            json.writeNumberField("rollingCountShortCircuited", commandMetrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            json.writeNumberField("rollingCountSuccess", commandMetrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            json.writeNumberField("rollingCountThreadPoolRejected", commandMetrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            json.writeNumberField("rollingCountTimeout", commandMetrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));

            json.writeNumberField("currentConcurrentExecutionCount", commandMetrics.getCurrentConcurrentExecutionCount());
            json.writeNumberField("rollingMaxConcurrentExecutionCount", commandMetrics.getRollingMaxConcurrentExecutions());

            // latency percentiles
            json.writeNumberField("latencyExecute_mean", commandMetrics.getExecutionTimeMean());
            json.writeObjectFieldStart("latencyExecute");
            json.writeNumberField("0", commandMetrics.getExecutionTimePercentile(0));
            json.writeNumberField("25", commandMetrics.getExecutionTimePercentile(25));
            json.writeNumberField("50", commandMetrics.getExecutionTimePercentile(50));
            json.writeNumberField("75", commandMetrics.getExecutionTimePercentile(75));
            json.writeNumberField("90", commandMetrics.getExecutionTimePercentile(90));
            json.writeNumberField("95", commandMetrics.getExecutionTimePercentile(95));
            json.writeNumberField("99", commandMetrics.getExecutionTimePercentile(99));
            json.writeNumberField("99.5", commandMetrics.getExecutionTimePercentile(99.5));
            json.writeNumberField("100", commandMetrics.getExecutionTimePercentile(100));
            json.writeEndObject();
            //
            json.writeNumberField("latencyTotal_mean", commandMetrics.getTotalTimeMean());
            json.writeObjectFieldStart("latencyTotal");
            json.writeNumberField("0", commandMetrics.getTotalTimePercentile(0));
            json.writeNumberField("25", commandMetrics.getTotalTimePercentile(25));
            json.writeNumberField("50", commandMetrics.getTotalTimePercentile(50));
            json.writeNumberField("75", commandMetrics.getTotalTimePercentile(75));
            json.writeNumberField("90", commandMetrics.getTotalTimePercentile(90));
            json.writeNumberField("95", commandMetrics.getTotalTimePercentile(95));
            json.writeNumberField("99", commandMetrics.getTotalTimePercentile(99));
            json.writeNumberField("99.5", commandMetrics.getTotalTimePercentile(99.5));
            json.writeNumberField("100", commandMetrics.getTotalTimePercentile(100));
            json.writeEndObject();

            // property values for reporting what is actually seen by the command rather than what was set somewhere
            HystrixCommandProperties commandProperties = commandMetrics.getProperties();

            json.writeNumberField("propertyValue_circuitBreakerRequestVolumeThreshold", commandProperties.circuitBreakerRequestVolumeThreshold().get());
            json.writeNumberField("propertyValue_circuitBreakerSleepWindowInMilliseconds", commandProperties.circuitBreakerSleepWindowInMilliseconds().get());
            json.writeNumberField("propertyValue_circuitBreakerErrorThresholdPercentage", commandProperties.circuitBreakerErrorThresholdPercentage().get());
            json.writeBooleanField("propertyValue_circuitBreakerForceOpen", commandProperties.circuitBreakerForceOpen().get());
            json.writeBooleanField("propertyValue_circuitBreakerForceClosed", commandProperties.circuitBreakerForceClosed().get());
            json.writeBooleanField("propertyValue_circuitBreakerEnabled", commandProperties.circuitBreakerEnabled().get());

            json.writeStringField("propertyValue_executionIsolationStrategy", commandProperties.executionIsolationStrategy().get().name());
            json.writeNumberField("propertyValue_executionIsolationThreadTimeoutInMilliseconds", commandProperties.executionTimeoutInMilliseconds().get());
            json.writeNumberField("propertyValue_executionTimeoutInMilliseconds", commandProperties.executionTimeoutInMilliseconds().get());
            json.writeBooleanField("propertyValue_executionIsolationThreadInterruptOnTimeout", commandProperties.executionIsolationThreadInterruptOnTimeout().get());
            json.writeStringField("propertyValue_executionIsolationThreadPoolKeyOverride", commandProperties.executionIsolationThreadPoolKeyOverride().get());
            json.writeNumberField("propertyValue_executionIsolationSemaphoreMaxConcurrentRequests", commandProperties.executionIsolationSemaphoreMaxConcurrentRequests().get());
            json.writeNumberField("propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests", commandProperties.fallbackIsolationSemaphoreMaxConcurrentRequests().get());

                    /*
                     * The following are commented out as these rarely change and are verbose for streaming for something people don't change.
                     * We could perhaps allow a property or request argument to include these.
                     */

            //                    json.put("propertyValue_metricsRollingPercentileEnabled", commandProperties.metricsRollingPercentileEnabled().get());
            //                    json.put("propertyValue_metricsRollingPercentileBucketSize", commandProperties.metricsRollingPercentileBucketSize().get());
            //                    json.put("propertyValue_metricsRollingPercentileWindow", commandProperties.metricsRollingPercentileWindowInMilliseconds().get());
            //                    json.put("propertyValue_metricsRollingPercentileWindowBuckets", commandProperties.metricsRollingPercentileWindowBuckets().get());
            //                    json.put("propertyValue_metricsRollingStatisticalWindowBuckets", commandProperties.metricsRollingStatisticalWindowBuckets().get());
            json.writeNumberField("propertyValue_metricsRollingStatisticalWindowInMilliseconds", commandProperties.metricsRollingStatisticalWindowInMilliseconds().get());

            json.writeBooleanField("propertyValue_requestCacheEnabled", commandProperties.requestCacheEnabled().get());
            json.writeBooleanField("propertyValue_requestLogEnabled", commandProperties.requestLogEnabled().get());

            json.writeNumberField("reportingHosts", 1); // this will get summed across all instances in a cluster
            json.writeStringField("threadPool", commandMetrics.getThreadPoolKey().name());

            json.writeEndObject();
            json.close();

            return jsonString.getBuffer().toString();
        }

        private boolean hasExecutedCommandsOnThread(HystrixThreadPoolMetrics threadPoolMetrics) {
            return threadPoolMetrics.getCurrentCompletedTaskCount().intValue() > 0;
        }

        private String getThreadPoolJson(HystrixThreadPoolMetrics threadPoolMetrics) throws IOException {
            HystrixThreadPoolKey key = threadPoolMetrics.getThreadPoolKey();
            StringWriter jsonString = new StringWriter();
            JsonGenerator json = jsonFactory.createJsonGenerator(jsonString);
            json.writeStartObject();

            json.writeStringField("type", "HystrixThreadPool");
            json.writeStringField("name", key.name());
            json.writeNumberField("currentTime", System.currentTimeMillis());

            json.writeNumberField("currentActiveCount", threadPoolMetrics.getCurrentActiveCount().intValue());
            json.writeNumberField("currentCompletedTaskCount", threadPoolMetrics.getCurrentCompletedTaskCount().longValue());
            json.writeNumberField("currentCorePoolSize", threadPoolMetrics.getCurrentCorePoolSize().intValue());
            json.writeNumberField("currentLargestPoolSize", threadPoolMetrics.getCurrentLargestPoolSize().intValue());
            json.writeNumberField("currentMaximumPoolSize", threadPoolMetrics.getCurrentMaximumPoolSize().intValue());
            json.writeNumberField("currentPoolSize", threadPoolMetrics.getCurrentPoolSize().intValue());
            json.writeNumberField("currentQueueSize", threadPoolMetrics.getCurrentQueueSize().intValue());
            json.writeNumberField("currentTaskCount", threadPoolMetrics.getCurrentTaskCount().longValue());
            json.writeNumberField("rollingCountThreadsExecuted", threadPoolMetrics.getRollingCount(HystrixRollingNumberEvent.THREAD_EXECUTION));
            json.writeNumberField("rollingMaxActiveThreads", threadPoolMetrics.getRollingMaxActiveThreads());
            json.writeNumberField("rollingCountCommandRejections", threadPoolMetrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));

            json.writeNumberField("propertyValue_queueSizeRejectionThreshold", threadPoolMetrics.getProperties().queueSizeRejectionThreshold().get());
            json.writeNumberField("propertyValue_metricsRollingStatisticalWindowInMilliseconds", threadPoolMetrics.getProperties().metricsRollingStatisticalWindowInMilliseconds().get());

            json.writeNumberField("reportingHosts", 1); // this will get summed across all instances in a cluster

            json.writeEndObject();
            json.close();

            return jsonString.getBuffer().toString();
        }

        private String getCollapserJson(HystrixCollapserMetrics collapserMetrics) throws IOException {
            HystrixCollapserKey key = collapserMetrics.getCollapserKey();
            StringWriter jsonString = new StringWriter();
            JsonGenerator json = jsonFactory.createJsonGenerator(jsonString);
            json.writeStartObject();

            json.writeStringField("type", "HystrixCollapser");
            json.writeStringField("name", key.name());
            json.writeNumberField("currentTime", System.currentTimeMillis());

            json.writeNumberField("rollingCountRequestsBatched", collapserMetrics.getRollingCount(HystrixRollingNumberEvent.COLLAPSER_REQUEST_BATCHED));
            json.writeNumberField("rollingCountBatches", collapserMetrics.getRollingCount(HystrixRollingNumberEvent.COLLAPSER_BATCH));
            json.writeNumberField("rollingCountResponsesFromCache", collapserMetrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            // batch size percentiles
            json.writeNumberField("batchSize_mean", collapserMetrics.getBatchSizeMean());
            json.writeObjectFieldStart("batchSize");
            json.writeNumberField("25", collapserMetrics.getBatchSizePercentile(25));
            json.writeNumberField("50", collapserMetrics.getBatchSizePercentile(50));
            json.writeNumberField("75", collapserMetrics.getBatchSizePercentile(75));
            json.writeNumberField("90", collapserMetrics.getBatchSizePercentile(90));
            json.writeNumberField("95", collapserMetrics.getBatchSizePercentile(95));
            json.writeNumberField("99", collapserMetrics.getBatchSizePercentile(99));
            json.writeNumberField("99.5", collapserMetrics.getBatchSizePercentile(99.5));
            json.writeNumberField("100", collapserMetrics.getBatchSizePercentile(100));
            json.writeEndObject();

            // shard size percentiles (commented-out for now)
            //json.writeNumberField("shardSize_mean", collapserMetrics.getShardSizeMean());
            //json.writeObjectFieldStart("shardSize");
            //json.writeNumberField("25", collapserMetrics.getShardSizePercentile(25));
            //json.writeNumberField("50", collapserMetrics.getShardSizePercentile(50));
            //json.writeNumberField("75", collapserMetrics.getShardSizePercentile(75));
            //json.writeNumberField("90", collapserMetrics.getShardSizePercentile(90));
            //json.writeNumberField("95", collapserMetrics.getShardSizePercentile(95));
            //json.writeNumberField("99", collapserMetrics.getShardSizePercentile(99));
            //json.writeNumberField("99.5", collapserMetrics.getShardSizePercentile(99.5));
            //json.writeNumberField("100", collapserMetrics.getShardSizePercentile(100));
            //json.writeEndObject();

            //json.writeNumberField("propertyValue_metricsRollingStatisticalWindowInMilliseconds", collapserMetrics.getProperties().metricsRollingStatisticalWindowInMilliseconds().get());
            json.writeBooleanField("propertyValue_requestCacheEnabled", collapserMetrics.getProperties().requestCacheEnabled().get());
            json.writeNumberField("propertyValue_maxRequestsInBatch", collapserMetrics.getProperties().maxRequestsInBatch().get());
            json.writeNumberField("propertyValue_timerDelayInMilliseconds", collapserMetrics.getProperties().timerDelayInMilliseconds().get());

            json.writeNumberField("reportingHosts", 1); // this will get summed across all instances in a cluster

            json.writeEndObject();
            json.close();

            return jsonString.getBuffer().toString();
        }
    }

    private class MetricsPollerThreadFactory implements ThreadFactory {
        private static final String MetricsThreadName = "HystrixMetricPoller";

        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

        public Thread newThread(Runnable r) {
            Thread thread = defaultFactory.newThread(r);
            thread.setName(MetricsThreadName);
            return thread;
        }
    }
}
