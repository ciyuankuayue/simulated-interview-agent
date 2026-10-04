package com.bupt.mockinterview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7-1：行为面试官角色枚举的解析/序列化与出题任务句。
 * 重点：行为角色走 displayName 落库全链路与既有两角色一致；
 * questionTask 行为句绝不能含「紧扣 JD 考点」（否则与行为 systemPrompt 矛盾导致首题跑偏）。
 */
class RoleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 行为面试官按展示名解析() {
        assertEquals(Role.BEHAVIOR_INTERVIEWER, Role.fromDisplayName("行为面试官"));
        assertEquals(Role.BEHAVIOR_INTERVIEWER, Role.fromDisplayName("  行为面试官 "));
    }

    @Test
    void parse支持三角色且剔除Critic() {
        List<Role> roles = Role.parse(
                "[\"技术深挖官\",\"项目质疑官\",\"行为面试官\",\"Critic\"]", mapper);
        assertEquals(List.of(Role.TECH_DIGGER, Role.PROJECT_CHALLENGER, Role.BEHAVIOR_INTERVIEWER), roles);
    }

    @Test
    void parse纯行为场只含行为面试官() {
        assertEquals(List.of(Role.BEHAVIOR_INTERVIEWER), Role.parse("[\"行为面试官\"]", mapper));
    }

    @Test
    void parse空与非法回退技术深挖官() {
        assertEquals(List.of(Role.TECH_DIGGER), Role.parse(null, mapper));
        assertEquals(List.of(Role.TECH_DIGGER), Role.parse("", mapper));
        assertEquals(List.of(Role.TECH_DIGGER), Role.parse("[\"不存在的角色\"]", mapper));
        assertEquals(List.of(Role.TECH_DIGGER), Role.parse("not-json", mapper));
    }

    @Test
    void parse去重() {
        List<Role> roles = Role.parse("[\"行为面试官\",\"行为面试官\"]", mapper);
        assertEquals(List.of(Role.BEHAVIOR_INTERVIEWER), roles);
    }

    @Test
    void toJson与parse往返一致() {
        List<Role> roles = List.of(Role.TECH_DIGGER, Role.BEHAVIOR_INTERVIEWER);
        assertEquals(roles, Role.parse(Role.toJson(roles, mapper), mapper));
    }

    @Test
    void 行为任务句不含技术考点措辞() {
        String task = Role.BEHAVIOR_INTERVIEWER.questionTask();
        assertTrue(task.contains("行为面试题"), "行为任务句应声明行为面试题: " + task);
        assertFalse(task.contains("紧扣 JD 考点"), "行为任务句不得要求紧扣 JD 考点: " + task);
        assertTrue(task.contains("不考任何具体技术知识点"), "行为任务句必须显式排除技术知识点: " + task);
    }

    @Test
    void 技术两角色任务句保留考点语义() {
        assertTrue(Role.TECH_DIGGER.questionTask().contains("紧扣 JD 考点"));
        assertTrue(Role.PROJECT_CHALLENGER.questionTask().contains("紧扣 JD 考点"));
    }

    @Test
    void critic不出题() {
        assertThrows(IllegalStateException.class, Role.CRITIC::questionTask);
    }
}
