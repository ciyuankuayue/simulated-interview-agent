package com.bupt.mockinterview.launch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Small-launch limits; authentication credentials and forwarded headers are never stored. */
@Data
@Component
@ConfigurationProperties(prefix = "app.launch.limits")
public class LaunchLimits {
    private boolean enabled = true;
    private int registrationsPerHour = 10;
    private int loginsPerMinute = 30;
    private int writesPerMinute = 60;
    private int maxRequestBytes = 1_048_576;
    private int maxTextChars = 20_000;
    private int maxBatchJds = 10;
    private long maxStoredChars = 1_000_000;
}
