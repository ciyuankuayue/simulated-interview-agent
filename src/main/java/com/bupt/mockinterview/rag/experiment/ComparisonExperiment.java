package com.bupt.mockinterview.rag.experiment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * L3 对比实验编排：同语料 + 同查询集，跑两套 Retriever，出召回率/耗时/代码量三指标。
 * 决策 #10 的关键路径产出，结论直接进 demo 讲。
 */
public class ComparisonExperiment {

    /** 单套方案的结果。codeLines 由调用方按真实实现行数填入（手写/向量库各自统计）。 */
    public record Result(String approach, double recallAtK, double avgLatencyMs, int codeLines) {
    }

    public static Result run(Retriever retriever, List<Doc> corpus, List<QueryCase> queries,
                             int topK, int codeLines) throws Exception {
        for (Doc d : corpus) {
            retriever.ingest(d.id(), d.text());
        }
        int hits = 0;
        long totalNs = 0;
        for (QueryCase q : queries) {
            long t0 = System.nanoTime();
            List<Retriever.ScoredDoc> top = retriever.search(q.query(), topK);
            totalNs += System.nanoTime() - t0;
            // 同一文档被切块会产生多个 chunk，按 docId 去重后判是否命中 ground truth
            Set<String> docs = new LinkedHashSet<>();
            for (Retriever.ScoredDoc s : top) {
                docs.add(s.docId());
            }
            if (docs.contains(q.expectedDocId())) {
                hits++;
            }
        }
        double recall = queries.isEmpty() ? 0 : (double) hits / queries.size();
        double avgMs = queries.isEmpty() ? 0 : totalNs / (1e6 * queries.size());
        return new Result(retriever.getClass().getSimpleName(), recall, avgMs, codeLines);
    }

    public static String toMarkdown(List<Result> results, int topK, int corpusSize, int queryCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("## L3 向量检索对比（corpus=").append(corpusSize).append(" 篇, queries=")
          .append(queryCount).append(", topK=").append(topK).append(")\n\n");
        sb.append("| 方案 | 召回率@").append(topK).append(" | 单查询耗时(ms) | 代码量(行) |\n");
        sb.append("|---|---|---|---|\n");
        for (Result r : results) {
            sb.append("| ").append(r.approach())
              .append(" | ").append(String.format("%.1f%%", r.recallAtK() * 100))
              .append(" | ").append(String.format("%.3f", r.avgLatencyMs()))
              .append(" | ").append(r.codeLines()).append(" |\n");
        }
        return sb.toString();
    }
}
