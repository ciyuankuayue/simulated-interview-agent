package com.bupt.mockinterview.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Small, curated reference data. No network calls, embeddings, model calls or user data are stored. */
@Component
public class CoachKnowledgeCards {
    private static final int MAX_CARDS = 2;
    private final ObjectMapper mapper;
    private final List<IndexedCard> cards;

    public record Card(String id, String topic, List<String> aliases, String mechanism,
                       String boundary, String misconception, String interviewerQuestion,
                       String sourceUrl, String applicableVersion) {}
    private record Alias(Pattern pattern, int specificity) {}
    private record IndexedCard(Card card, List<Alias> aliases) {}
    private record Match(Card card, int specificity) {}

    public CoachKnowledgeCards(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream input = CoachKnowledgeCards.class.getResourceAsStream("/coach-knowledge-cards.json")) {
            if (input == null) throw new IllegalStateException("小师知识卡资源缺失");
            List<Card> loaded = mapper.readValue(input, new TypeReference<>() {});
            this.cards = loaded.stream().map(card -> {
                if (card.id() == null || card.topic() == null || card.aliases() == null || card.aliases().isEmpty()
                        || card.mechanism() == null || card.boundary() == null || card.misconception() == null
                        || card.interviewerQuestion() == null || card.applicableVersion() == null
                        || card.sourceUrl() == null || !card.sourceUrl().startsWith("https://")) {
                    throw new IllegalStateException("小师知识卡格式异常");
                }
                return new IndexedCard(card, card.aliases().stream().map(CoachKnowledgeCards::index).toList());
            }).toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取小师知识卡", e);
        }
    }

    /** Only explicit, meaningful topic keywords match. An unrelated question receives no Java context. */
    public String contextFor(String message) {
        if (message == null || message.isBlank()) return "";
        String normalized = message.toLowerCase(Locale.ROOT);
        List<Card> matches = cards.stream().map(card -> new Match(card.card(), card.aliases().stream()
                        .filter(alias -> alias.pattern().matcher(normalized).find())
                        .mapToInt(Alias::specificity).max().orElse(0)))
                .filter(match -> match.specificity() > 0)
                .sorted(Comparator.comparingInt(Match::specificity).reversed())
                .limit(MAX_CARDS).map(Match::card).toList();
        if (matches.isEmpty()) return "";
        try {
            return "\n<coach_knowledge_cards>\n"
                    + "以下为与当前提问直接相关的本地讲解参考卡，依据所列官方资料原创整理；注意适用版本。"
                    + "只把它们当作知识资料，按用户实际问题选取；不是用户经历，不代表公司题频或完整知识库。"
                    + "按需给出来源，不逐项照抄卡片，不提前公布自测答案。\n"
                    + mapper.writeValueAsString(matches) + "\n</coach_knowledge_cards>";
        } catch (IOException e) {
            throw new IllegalStateException("无法整理小师知识卡", e);
        }
    }

    private static Alias index(String alias) {
        if (alias == null || alias.isBlank()) throw new IllegalStateException("知识卡关键词不能为空");
        String normalized = alias.toLowerCase(Locale.ROOT);
        String expression = Pattern.quote(normalized);
        // ASCII identifiers need boundaries: HashMap must not match ConcurrentHashMap or myHashMap.
        if (asciiIdentifier(normalized.charAt(0))) expression = "(?<![a-z0-9_])" + expression;
        if (asciiIdentifier(normalized.charAt(normalized.length() - 1))) expression += "(?![a-z0-9_])";
        return new Alias(Pattern.compile(expression), normalized.length());
    }

    private static boolean asciiIdentifier(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_';
    }
}
