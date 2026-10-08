package com.bupt.mockinterview.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4 S2 领域识别组件的确定性单测（零 LLM、零网络）。 */
class DomainInferenceTest {

    @Test
    void 前端JD命中前端() {
        Optional<String> r = DomainInference.infer("招聘前端工程师：熟练 Vue / React，理解 JavaScript", null);
        assertEquals(Optional.of("前端"), r);
    }

    @Test
    void Java后端JD命中Java后端() {
        Optional<String> r = DomainInference.infer("招聘 Java 后端：Spring Boot、JVM、MySQL 索引", null);
        assertEquals(Optional.of("Java 后端"), r);
    }

    @Test
    void 纯数字JD不命中_走空JD防御() {
        // "1111" 长度<8 且无语义关键词 → 不命中，调用方据此走「不假设固定领域」分支
        assertTrue(DomainInference.infer("1111", null).isEmpty());
        assertTrue(DomainInference.infer("2222", "2222").isEmpty());
    }

    @Test
    void 考公JD命中考公() {
        assertEquals(Optional.of("考公"), DomainInference.infer("备考公务员，行测申论冲刺", null));
    }

    @Test
    void 空输入不命中() {
        assertTrue(DomainInference.infer(null, null).isEmpty());
        assertTrue(DomainInference.infer("", "   ").isEmpty());
    }

    @Test
    void 简历片段可参与识别() {
        assertEquals(Optional.of("前端"), DomainInference.infer("岗位待定", "# 张三\n三年前端开发，主导 Vue 重构"));
    }
}
