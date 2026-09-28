## L3 向量检索对比（corpus=79 篇, queries=15, topK=3)

| 方案 | 召回率@3 | 单查询耗时(ms) | 代码量(行) |
|---|---|---|---|
| HandcraftedRetriever | 100.0% | 113.727 | 30 |
| LuceneHnswRetriever | 100.0% | 146.382 | 100 |
