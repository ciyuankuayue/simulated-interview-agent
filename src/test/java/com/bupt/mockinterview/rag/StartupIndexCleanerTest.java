package com.bupt.mockinterview.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R4 批 3：{@link StartupIndexCleaner} 启动清孤儿索引判定。
 * 四张资料表行数之和=0 → deleteAll；>0 不动；开关关 → 什么都不做。
 */
@ExtendWith(MockitoExtension.class)
class StartupIndexCleanerTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private VectorStore store;

    private StartupIndexCleaner cleanerWith(boolean enabled) {
        RagProperties props = new RagProperties();
        props.setCleanOnEmptySources(enabled);
        return new StartupIndexCleaner(props, store, jdbc);
    }

    @Test
    void emptySources_clearsIndex() throws Exception {
        when(jdbc.queryForObject(StartupIndexCleaner.COUNT_SQL, Long.class)).thenReturn(0L);

        cleanerWith(true).run(new DefaultApplicationArguments());

        verify(store).deleteAll();
    }

    @Test
    void nonEmptySources_keepsIndex() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);

        cleanerWith(true).run(new DefaultApplicationArguments());

        verify(store, never()).deleteAll();
    }

    @Test
    void disabled_doesNothing() throws Exception {
        cleanerWith(false).run(new DefaultApplicationArguments());

        verifyNoInteractions(jdbc);
        verify(store, never()).deleteAll();
    }
}
