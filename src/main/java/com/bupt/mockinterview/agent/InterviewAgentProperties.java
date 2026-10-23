package com.bupt.mockinterview.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Hard execution limits, independent of the model's suggested next action. */
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewAgentProperties {
    private boolean agentEnabled = true;
    private int maxModelCalls = 3;
    private int maxToolCalls = 4;
    private int maxOutputTokens = 4096;
    private long maxStepTokens = 64000;
    private long maxStepMillis = 60000;

    public boolean isAgentEnabled() { return agentEnabled; }
    public void setAgentEnabled(boolean value) { agentEnabled = value; }
    public int getMaxModelCalls() { return Math.max(1, Math.min(5, maxModelCalls)); }
    public void setMaxModelCalls(int value) { maxModelCalls = value; }
    public int getMaxToolCalls() { return Math.max(0, Math.min(8, maxToolCalls)); }
    public void setMaxToolCalls(int value) { maxToolCalls = value; }
    public int getMaxOutputTokens() { return Math.max(4096, Math.min(8192, maxOutputTokens)); }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public long getMaxStepTokens() { return Math.max(4096, Math.min(100000, maxStepTokens)); }
    public void setMaxStepTokens(long value) { maxStepTokens = value; }
    public long getMaxStepMillis() { return Math.max(1000, Math.min(180000, maxStepMillis)); }
    public void setMaxStepMillis(long value) { maxStepMillis = value; }
}
