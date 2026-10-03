package com.bupt.mockinterview.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RAG 向量索引配置，绑定 {@code app.rag.*}（R4）。
 *
 * <ul>
 *   <li>{@code app.rag.index-dir}：Lucene FSDirectory 索引目录。相对路径基于进程工作目录
 *       （{@code user.dir}）解析为绝对路径；配绝对路径原样使用。环境变量
 *       {@code APP_RAG_INDEX_DIR} 可覆盖（Spring relaxed binding）。</li>
 *   <li>{@code app.rag.dim}：向量维度，默认 1024（通义 qwen embedding 实测维度）。
 *       写入/查询前统一 pad-0/truncate 到该维度。</li>
 *   <li>{@code app.rag.clean-on-empty-sources}：启动时若四张资料表全空则清空向量索引
 *       （dev/H2 create-drop 防孤儿块）。默认 true；<b>application-mysql.yml 显式 false</b>。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.rag")
@Getter
@Setter
public class RagProperties {

    /** 索引目录（相对路径或绝对路径）。 */
    private String indexDir = "data/rag-index";

    /** 向量维度：写入/查询统一对齐到该长度（短补 0、长截断）。 */
    private int dim = 1024;

    /** 启动时资料表全空是否清空向量索引（dev/H2 防孤儿块；mysql profile 关）。 */
    private boolean cleanOnEmptySources = true;

    /**
     * 仅测试用：使用 ByteBuffersDirectory 内存索引（不落盘）。测试套件因不同 {@code @MockBean}
     * 组合会并存多个 Spring 上下文，各自 new 一个内存目录可避免争用同一 FSDirectory 的 write.lock；
     * 持久化能力由 POJO + @TempDir 的 LuceneVectorStoreTest 单独保证。生产/默认 false（落盘）。
     */
    private boolean ramDirectory = false;

    /**
     * 把配置的 indexDir 解析为绝对路径：相对路径基于 {@code user.dir}，绝对路径原样使用。
     * 避免 FSDirectory 依赖当前工作目录的歧义（R4 复审 #9）。
     */
    public Path resolveIndexDir() {
        Path p = Paths.get(indexDir);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return Paths.get(System.getProperty("user.dir")).resolve(p).toAbsolutePath().normalize();
    }
}
