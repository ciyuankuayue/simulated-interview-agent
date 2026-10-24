package com.bupt.mockinterview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoachKnowledgeCardsTest {
    private final CoachKnowledgeCards cards = new CoachKnowledgeCards(new ObjectMapper());

    @Test
    void directlyMatchedTopicCarriesMechanismBoundaryAndOfficialSource() {
        String context = cards.contextFor("能用比喻讲一下 volatile 吗？");
        assertThat(context).contains("java-volatile", "count++", "Java SE 17",
                "https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html");
        assertThat(context).doesNotContain("mysql-", "redis-");
    }

    @Test
    void unrelatedQuestionsAndGenericWordsReceiveNoJavaOrDatabaseTopic() {
        for (String message : new String[]{null, "", "我今天面试失败了", "教我 Python 列表推导式",
                "帮我准备产品经理用户访谈", "怎么提高产品在用户面前的可见性", "锁", "事务", "缓存", "broadcast", "myHashMapWrapper"}) {
            assertThat(cards.contextFor(message)).as("input=%s", message).isEmpty();
        }
    }

    @Test
    void asciiIdentifierDoesNotAccidentallyMatchLongerIdentifier() {
        String context = cards.contextFor("ConcurrentHashMap 的并发访问怎么理解？");
        assertThat(context).contains("java-concurrent-hashmap").doesNotContain("\"id\":\"java-hashmap\"");
    }

    @Test
    void atMostTwoCardsEvenWhenManyConceptsAppear() throws Exception {
        String context = cards.contextFor("比较 volatile、synchronized、ThreadLocal、HashMap 和 ConcurrentHashMap");
        JsonNode result = contextCards(context);
        assertThat(result.size()).isEqualTo(2);
        assertThat(context.length()).isLessThan(4500);
    }

    @Test
    void clearChineseTopicAndVersionSpecificSpringTopicMatch() {
        assertThat(cards.contextFor("联合索引为什么要考虑最左前缀？")).contains("mysql-composite-index", "MySQL 8.0");
        assertThat(cards.contextFor("为什么事务自调用会失效？")).contains("spring-transaction-proxy", "Spring Framework 6.1.x");
    }

    private JsonNode contextCards(String context) throws Exception {
        int start = context.indexOf("[{"), end = context.lastIndexOf("]");
        return new ObjectMapper().readTree(context.substring(start, end + 1));
    }
}
