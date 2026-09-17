package com.wude.nexusmind.knowledge.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusmind.document-task")
public class DocumentTaskProperties {

    private boolean enabled;
    private boolean workerEnabled = true;
    private int workerConcurrency = 2;
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration heartbeatInterval = Duration.ofSeconds(10);
    private Duration staleAfter = Duration.ofMinutes(5);
    private Duration recoveryInterval = Duration.ofSeconds(30);
    private int maxStaleRecoveries = 3;
    private Duration shutdownAwait = Duration.ofSeconds(30);

    public void validate() {
        if (workerConcurrency <= 0) throw new IllegalStateException("document-task worker-concurrency must be positive");
        requirePositive(pollInterval, "poll-interval");
        requirePositive(heartbeatInterval, "heartbeat-interval");
        requirePositive(staleAfter, "stale-after");
        requirePositive(recoveryInterval, "recovery-interval");
        requirePositive(shutdownAwait, "shutdown-await");
        if (staleAfter.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalStateException("document-task stale-after must be greater than heartbeat-interval");
        }
        if (maxStaleRecoveries < 0) throw new IllegalStateException("document-task max-stale-recoveries cannot be negative");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("document-task " + name + " must be positive");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public Duration getStaleAfter() { return staleAfter; }
    public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
    public Duration getRecoveryInterval() { return recoveryInterval; }
    public void setRecoveryInterval(Duration recoveryInterval) { this.recoveryInterval = recoveryInterval; }
    public int getMaxStaleRecoveries() { return maxStaleRecoveries; }
    public void setMaxStaleRecoveries(int maxStaleRecoveries) { this.maxStaleRecoveries = maxStaleRecoveries; }
    public Duration getShutdownAwait() { return shutdownAwait; }
    public void setShutdownAwait(Duration shutdownAwait) { this.shutdownAwait = shutdownAwait; }
}
