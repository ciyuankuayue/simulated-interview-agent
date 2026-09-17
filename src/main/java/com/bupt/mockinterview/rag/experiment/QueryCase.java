package com.bupt.mockinterview.rag.experiment;

/** 一条固定查询 + 它应命中的正确文档 id（ground truth）。 */
public record QueryCase(String query, String expectedDocId) {
}
