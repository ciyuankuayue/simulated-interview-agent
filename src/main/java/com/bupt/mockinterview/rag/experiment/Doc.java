package com.bupt.mockinterview.rag.experiment;

/** 一篇被向量化的文档（语料单元）。id 用于 ground truth 命中判定。 */
public record Doc(String id, String text) {
}
