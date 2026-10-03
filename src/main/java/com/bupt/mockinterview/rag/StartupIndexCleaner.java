package com.bupt.mockinterview.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * dev 模式启动清孤儿向量索引（R4 复审 #3）。
 *
 * <p>H2 默认 profile {@code ddl-auto=create-drop}：每次启动资料库都是空的，但 Lucene 索引目录是
 * 落盘的，若不清，上一轮的向量块会成为「悬空块」（仍按 userId 被本用户检索到，却已无对应资料行）。
 *
 * <p>策略：开关 {@code app.rag.clean-on-empty-sources}（默认 true，mysql profile 显式 false）打开时，
 * 启动统计四张资料表行数之和；=0 则 {@link VectorStore#deleteAll()} 清空向量索引并打 WARN；>0 不动。
 * 生产 mysql 资料持久化，不存在此问题，故关闭。
 *
 * <p>边界：userId pre-filter 保证跨用户不泄露；本清理只解决「本用户旧向量悬空」的开发期困扰。
 */
@Component
public class StartupIndexCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIndexCleaner.class);

    /** 四张语料源表行数之和（JD 是查询源，不在内）。H2(MySQL 模式) 与 MySQL 8 均支持该标量子查询写法。 */
    static final String COUNT_SQL =
            "SELECT (SELECT COUNT(*) FROM resume_version)"
                    + " + (SELECT COUNT(*) FROM note)"
                    + " + (SELECT COUNT(*) FROM project_doc)"
                    + " + (SELECT COUNT(*) FROM real_interview_record)";

    private final RagProperties ragProps;
    private final VectorStore store;
    private final JdbcTemplate jdbcTemplate;

    public StartupIndexCleaner(RagProperties ragProps, VectorStore store, JdbcTemplate jdbcTemplate) {
        this.ragProps = ragProps;
        this.store = store;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ragProps.isCleanOnEmptySources()) {
            log.debug("启动清索引开关关闭（app.rag.clean-on-empty-sources=false），跳过");
            return;
        }
        try {
            Long total = jdbcTemplate.queryForObject(COUNT_SQL, Long.class);
            if (total != null && total == 0L) {
                store.deleteAll();
                log.warn("资料表为空，已清空向量索引（dev/H2 create-drop 防孤儿块）。"
                        + "生产 mysql profile 会通过 app.rag.clean-on-empty-sources=false 关闭本清理。");
            } else {
                log.info("资料表共 {} 行，向量索引保留（doc={}）", total, store.size());
            }
        } catch (Exception e) {
            // 清理器绝不能阻断应用启动；任何异常只告警。
            log.warn("启动检查/清理向量索引失败（不影响启动）：{}", e.getMessage());
        }
    }
}
