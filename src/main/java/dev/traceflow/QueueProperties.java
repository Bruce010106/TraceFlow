package dev.traceflow;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix="traceflow.queue")
public class QueueProperties {
    private boolean enabled=true;
    @Min(1) @Max(8) private int concurrency=2;
    @Min(1) @Max(10) private int maxAttempts=3;
    @Min(200) @Max(30000) private long pollMs=500;
    @Min(5) @Max(300) private int leaseSeconds=30;
    @Min(200) @Max(60000) private long heartbeatMs=5000;
    @Min(200) @Max(60000) private long retryBaseMs=2000;
    @Min(200) @Max(3600000) private long retryMaxMs=60000;
    @AssertTrue(message="heartbeat must be less than half the lease; retry maximum must not be below base")
    public boolean isTimingValid(){return heartbeatMs*2<leaseSeconds*1000L&&retryMaxMs>=retryBaseMs;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public int getConcurrency(){return concurrency;} public void setConcurrency(int value){concurrency=value;}
    public int getMaxAttempts(){return maxAttempts;} public void setMaxAttempts(int value){maxAttempts=value;}
    public long getPollMs(){return pollMs;} public void setPollMs(long value){pollMs=value;}
    public int getLeaseSeconds(){return leaseSeconds;} public void setLeaseSeconds(int value){leaseSeconds=value;}
    public long getHeartbeatMs(){return heartbeatMs;} public void setHeartbeatMs(long value){heartbeatMs=value;}
    public long getRetryBaseMs(){return retryBaseMs;} public void setRetryBaseMs(long value){retryBaseMs=value;}
    public long getRetryMaxMs(){return retryMaxMs;} public void setRetryMaxMs(long value){retryMaxMs=value;}
}
