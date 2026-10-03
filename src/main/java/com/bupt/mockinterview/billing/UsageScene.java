package com.bupt.mockinterview.billing;

/**
 * 一次计费调用的业务场景（写入 llm_usage_log.scene）。
 */
public enum UsageScene {
    JD_PARSE,
    FOLLOWUP,
    SCORE,
    CRITIC,
    CHAT,
    EMBED_INGEST,
    EMBED_QUERY
}
