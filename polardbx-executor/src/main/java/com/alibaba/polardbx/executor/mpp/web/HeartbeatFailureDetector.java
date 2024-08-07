/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.mpp.web;

import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.executor.mpp.Threads;
import com.alibaba.polardbx.executor.mpp.client.MppMediaTypes;
import com.alibaba.polardbx.executor.mpp.util.Failures;
import com.alibaba.polardbx.executor.mpp.util.ImmutableCollectors;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.discovery.client.ServiceDescriptor;
import io.airlift.discovery.client.ServiceSelector;
import io.airlift.discovery.client.ServiceType;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.ResponseHandler;
import io.airlift.node.NodeInfo;
import io.airlift.stats.DecayCounter;
import io.airlift.stats.ExponentialDecay;
import io.airlift.units.Duration;
import org.joda.time.DateTime;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.http.client.Request.Builder.prepareHead;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class HeartbeatFailureDetector
    implements FailureDetector {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatFailureDetector.class);

    private final ServiceSelector selector;
    private final HttpClient httpClient;
    private final NodeInfo nodeInfo;

    private final ScheduledExecutorService executor =
        newSingleThreadScheduledExecutor(Threads.daemonThreadsNamed("failure-detector"));

    // monitoring tasks by service id
    private final ConcurrentMap<UUID, MonitoringTask> tasks = new ConcurrentHashMap<>();

    private final double failureRatioThreshold;
    private final Duration heartbeat;
    private final boolean isEnabled;
    private final Duration warmupInterval;
    private final Duration gcGraceInterval;
    private final Duration failureInterval;

    private final AtomicBoolean started = new AtomicBoolean();

    @Inject
    public HeartbeatFailureDetector(
        @ServiceType(MppMediaTypes.MPP_POLARDBX) ServiceSelector selector,
        @ForFailureDetector HttpClient httpClient,
        FailureDetectorConfig config,
        NodeInfo nodeInfo) {
        requireNonNull(selector, "selector is null");
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(nodeInfo, "nodeInfo is null");
        requireNonNull(config, "config is null");
        checkArgument(config.getHeartbeatInterval().toMillis() >= 1, "heartbeat interval must be >= 1ms");

        this.selector = selector;
        this.httpClient = httpClient;
        this.nodeInfo = nodeInfo;

        this.failureRatioThreshold = config.getFailureRatioThreshold();
        this.heartbeat = config.getHeartbeatInterval();
        this.warmupInterval = config.getWarmupInterval();
        this.gcGraceInterval = config.getExpirationGraceInterval();
        this.failureInterval = config.getFailureInterval();

        this.isEnabled = config.isEnabled();
    }

    @PostConstruct
    public void start() {
        if (isEnabled && started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        updateMonitoredServices();
                    } catch (Throwable e) {
                        // ignore to avoid getting unscheduled
                        log.warn("Error updating services", e);
                    }
                }
            }, 0, 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public Set<ServiceDescriptor> getFailed() {
        return tasks.values().stream()
            .filter(MonitoringTask::isFailed)
            .map(MonitoringTask::getService)
            .collect(ImmutableCollectors.toImmutableSet());
    }

    @Managed(description = "Number of failed services")
    public int getFailedCount() {
        return getFailed().size();
    }

    @Managed(description = "Total number of known services")
    public int getTotalCount() {
        return tasks.size();
    }

    @Managed
    public int getActiveCount() {
        return tasks.size() - getFailed().size();
    }

    public Map<ServiceDescriptor, Stats> getStats() {
        ImmutableMap.Builder<ServiceDescriptor, Stats> builder = ImmutableMap.builder();
        for (MonitoringTask task : tasks.values()) {
            builder.put(task.getService(), task.getStats());
        }
        return builder.build();
    }

    @VisibleForTesting
    void updateMonitoredServices() {
        Set<ServiceDescriptor> online = selector.selectAllServices().stream()
            .filter(descriptor -> !nodeInfo.getNodeId().equals(descriptor.getNodeId()))
            .collect(ImmutableCollectors.toImmutableSet());

        Set<UUID> onlineIds = online.stream()
            .map(ServiceDescriptor::getId)
            .collect(ImmutableCollectors.toImmutableSet());

        // make sure only one thread is updating the registrations
        synchronized (tasks) {
            // 1. remove expired tasks
            List<UUID> expiredIds = tasks.values().stream()
                .filter(MonitoringTask::isExpired)
                .map(MonitoringTask::getService)
                .map(ServiceDescriptor::getId)
                .collect(ImmutableCollectors.toImmutableList());

            logExpiredNodes(expiredIds, tasks);
            tasks.keySet().removeAll(expiredIds);

            // 2. disable offline services
            tasks.values().stream()
                .filter(task -> !onlineIds.contains(task.getService().getId()))
                .forEach(MonitoringTask::disable);

            // 3. create tasks for new services
            Set<ServiceDescriptor> newServices = online.stream()
                .filter(service -> !tasks.keySet().contains(service.getId()))
                .collect(ImmutableCollectors.toImmutableSet());

            for (ServiceDescriptor service : newServices) {
                URI uri = getHttpUri(service);

                if (uri != null) {
                    tasks.put(service.getId(), new MonitoringTask(service, uri));
                }
            }

            // 4. enable all online tasks (existing plus newly created)
            tasks.values().stream()
                .filter(task -> onlineIds.contains(task.getService().getId()))
                .forEach(MonitoringTask::enable);
        }
    }

    protected static void logExpiredNodes(List<UUID> expiredIds, ConcurrentMap<UUID, MonitoringTask> tasks) {
        if (!expiredIds.isEmpty()) {
            for (int i = 0; i < expiredIds.size(); i++) {
                UUID expiredId = expiredIds.get(i);
                MonitoringTask expiredTask = tasks.get(expiredId);
                if (expiredTask != null) {
                    log.error("Node is expired, uri:" + getHttpUri(expiredTask.getService())
                        + " nodeId:" + expiredTask.getService().getNodeId() + " uuid:" + expiredTask.getService()
                        .getId());
                }
            }
        }
    }

    private static URI getHttpUri(ServiceDescriptor service) {
        try {
            String uri = service.getProperties().get("http");
            if (uri != null) {
                return new URI(uri);
            }
        } catch (URISyntaxException e) {
            // ignore, not a valid http uri
        }

        return null;
    }

    @ThreadSafe
    private class MonitoringTask {
        private final ServiceDescriptor service;
        private final URI uri;
        private final Stats stats;

        @GuardedBy("this")
        private ScheduledFuture<?> future;

        @GuardedBy("this")
        private Long disabledTimestamp;

        @GuardedBy("this")
        private Long successTransitionTimestamp;

        private MonitoringTask(ServiceDescriptor service, URI uri) {
            this.uri = uri;
            this.service = service;
            this.stats = new Stats(uri, service.getNodeId(), service.getId(), failureInterval);
        }

        public Stats getStats() {
            return stats;
        }

        public ServiceDescriptor getService() {
            return service;
        }

        public synchronized void enable() {
            if (future == null) {
                future = executor.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ping();
                            updateState();
                        } catch (Throwable e) {
                            // ignore to avoid getting unscheduled
                            log.warn("Error pinging service " + service.getId() + " " + uri + " " + service.getNodeId(),
                                e);
                        }
                    }
                }, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
                disabledTimestamp = null;
                log.warn(
                    "Enable node detect, uri:" + uri + " nodeId:" + service.getNodeId() + " uuid:" + service.getId());
            }
        }

        public synchronized void disable() {
            if (future != null) {
                future.cancel(true);
                future = null;
                disabledTimestamp = System.nanoTime();
                log.warn(
                    "Disable node detect, uri:" + uri + " nodeId:" + service.getNodeId() + " uuid:" + service.getId());
            }
        }

        public synchronized boolean isExpired() {
            return future == null && disabledTimestamp != null
                && Duration.nanosSince(disabledTimestamp).compareTo(gcGraceInterval) > 0;
        }

        public synchronized boolean isFailed() {
            //            return future == null || // are we disabled?
            //                    successTransitionTimestamp == null || // are we in success state?
            //                    Duration.nanosSince(successTransitionTimestamp).compareTo(warmupInterval) < 0; // are we within the warmup period?
            return future == null || // are we disabled?
                successTransitionTimestamp == null; // are we in success state?
        }

        private void ping() {
            try {
                stats.recordStart();
                httpClient.executeAsync(prepareHead().setUri(uri).build(), new ResponseHandler<Object, Exception>() {
                    @Override
                    public Exception handleException(Request request, Exception exception) {
                        // ignore error
                        stats.recordFailure(exception);

                        // TODO: this will technically cause an NPE in httpClient, but it's not triggered because
                        // we never call get() on the response future. This behavior needs to be fixed in airlift
                        return null;
                    }

                    @Override
                    public Object handle(Request request, Response response)
                        throws Exception {
                        stats.recordSuccess();
                        return null;
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Error scheduling request for " + uri + " " + service.getNodeId(), e);
            }
        }

        private synchronized void updateState() {
            // is this an over/under transition?
            if (stats.isFailed()) {
                successTransitionTimestamp = null;
            } else if (successTransitionTimestamp == null) {
                successTransitionTimestamp = System.nanoTime();
            }
        }
    }

    public static class Stats {
        private final long start = System.nanoTime();
        private final URI uri;
        private final String nodeId;
        private final UUID uuid;

        private final DecayCounter recentRequests = new DecayCounter(ExponentialDecay.oneMinute());
        private final DecayCounter recentFailures = new DecayCounter(ExponentialDecay.oneMinute());
        private final DecayCounter recentSuccesses = new DecayCounter(ExponentialDecay.oneMinute());
        private final AtomicReference<DateTime> lastRequestTime = new AtomicReference<>();
        private final AtomicReference<DateTime> lastResponseTime = new AtomicReference<>();

        private long successResponseTime;
        private Duration failureInterval;

        private boolean nodeDown;

        @GuardedBy("this")
        private final Map<Class<? extends Throwable>, DecayCounter> failureCountByType = new HashMap<>();

        public Stats(URI uri, String nodeId, UUID uuid, Duration failureInterval) {
            this.uri = uri;
            this.nodeId = nodeId;
            this.uuid = uuid;
            this.failureInterval = failureInterval;
            successResponseTime = System.currentTimeMillis();
        }

        public void recordStart() {
            recentRequests.add(1);
            lastRequestTime.set(new DateTime());
        }

        public void recordSuccess() {
            successResponseTime = System.currentTimeMillis();
            recentSuccesses.add(1);
            lastResponseTime.set(new DateTime());
            if (nodeDown) {
                log.error("Node is recover, detected by heartbeat failure detector, uri:" + uri + " nodeId:" + nodeId
                    + " uuid:" + uuid);
            }
            nodeDown = false;
        }

        public void recordFailure(Exception exception) {
            recentFailures.add(1);
            lastResponseTime.set(new DateTime());

            if (!nodeDown && Failures.isConnectionRefused(exception)) {
                log.error(
                    "Node is down, detected by heartbeat failure detector, uri:" + uri + " nodeId:" + nodeId + " uuid:"
                        + uuid);
                nodeDown = true;
            }

            Throwable cause = exception;
            while (cause.getClass() == RuntimeException.class && cause.getCause() != null) {
                cause = cause.getCause();
            }

            synchronized (this) {
                DecayCounter counter = failureCountByType.get(cause.getClass());
                if (counter == null) {
                    counter = new DecayCounter(ExponentialDecay.oneMinute());
                    failureCountByType.put(cause.getClass(), counter);
                }
                counter.add(1);
            }
        }

        public boolean isFailed() {
            return nodeDown || (System.currentTimeMillis() - successResponseTime > failureInterval.toMillis());
        }

        @JsonProperty
        public Duration getAge() {
            return Duration.nanosSince(start);
        }

        @JsonProperty
        public URI getUri() {
            return uri;
        }

        @JsonProperty
        public double getRecentFailures() {
            return recentFailures.getCount();
        }

        @JsonProperty
        public double getRecentSuccesses() {
            return recentSuccesses.getCount();
        }

        @JsonProperty
        public double getRecentRequests() {
            return recentRequests.getCount();
        }

        @JsonProperty
        public double getRecentFailureRatio() {
            return recentFailures.getCount() / recentRequests.getCount();
        }

        @JsonProperty
        public DateTime getLastRequestTime() {
            return lastRequestTime.get();
        }

        @JsonProperty
        public DateTime getLastResponseTime() {
            return lastResponseTime.get();
        }

        @JsonProperty
        public synchronized Map<String, Double> getRecentFailuresByType() {
            ImmutableMap.Builder<String, Double> builder = ImmutableMap.builder();
            for (Map.Entry<Class<? extends Throwable>, DecayCounter> entry : failureCountByType.entrySet()) {
                builder.put(entry.getKey().getName(), entry.getValue().getCount());
            }
            return builder.build();
        }
    }
}