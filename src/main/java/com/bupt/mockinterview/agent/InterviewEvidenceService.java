package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.ResumeVersion;
import com.bupt.mockinterview.domain.repository.ResumeVersionRepository;
import com.bupt.mockinterview.rag.KnowledgeChunk;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.security.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动态面试的只读证据入口：本场 JD/所选简历留原文快照，其他资料按需检索。
 * 不允许把向量库中其他简历版本拼入本场上下文，也不把匿名身份解释为跨用户检索。
 * 项目/笔记/真实面试记录仍读当前索引；运行时应将已经使用的证据随决策持久化。
 */
@Service
public class InterviewEvidenceService {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_CONTENT_CHARS = 1500;
    private static final Set<String> MATERIAL_TYPES = Set.of("project_doc", "note", "real_interview", "real_interview_record");
    private static final Pattern QUERY_WORDS = Pattern.compile("[\\p{IsLatin}\\p{N}_+#.]{2,}");
    private static final Pattern HAN_PHRASES = Pattern.compile("\\p{IsHan}{2,}");

    private final ResumeVersionRepository resumes;
    private final RagService rag;
    private final CurrentUserService currentUser;

    public InterviewEvidenceService(ResumeVersionRepository resumes, RagService rag,
                                    CurrentUserService currentUser) {
        this.resumes = Objects.requireNonNull(resumes);
        this.rag = Objects.requireNonNull(rag);
        this.currentUser = Objects.requireNonNull(currentUser);
    }

    public record Snapshot(String jdText, Long resumeId, String resumeText) {}

    public record Evidence(String id, String sourceType, String sourceId, String content) {}

    /** 所有权检查先于取原文；不指定版本时，简历快照为空，不隐式选取其他版本。 */
    public Snapshot snapshot(JdProfile jd, Long resumeId) {
        Long userId = requireUserId();
        if (jd == null || jd.getUser() == null || !userId.equals(jd.getUser().getId())) {
            throw new IllegalArgumentException("JD 不存在或不属于当前用户");
        }
        String resumeText = "";
        if (resumeId != null) {
            ResumeVersion selected = resumes.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("简历版本不存在或不属于当前用户"));
            if (selected.getUser() == null || !userId.equals(selected.getUser().getId())) {
                throw new IllegalArgumentException("简历版本不存在或不属于当前用户");
            }
            resumeText = text(selected.getContent());
        }
        return new Snapshot(text(jd.getRawText()), resumeId, resumeText);
    }

    /**
     * 仅接受运行时从当前用户会话载入的快照。向量候选上限 20，最终最多 5 条、每条 1500 字符。
     * 选定简历的词项匹配直接使用快照，避免召回其他版本；其余只允许本人三类资料。
     * 额度、并发和普通检索异常均原样交给运行时工具层处理。
     */
    public List<Evidence> search(Snapshot snapshot, String query, int topK) throws Exception {
        Long userId = requireUserId();
        Objects.requireNonNull(snapshot, "本场资料快照不能为空");
        int limit = Math.min(MAX_RESULTS, Math.max(0, topK));
        if (limit == 0 || query == null || query.isBlank()) {
            return List.of();
        }

        Map<String, Evidence> results = new LinkedHashMap<>();
        if (snapshot.resumeId() != null && matchesResume(snapshot.resumeText(), query)) {
            String sourceId = snapshot.resumeId().toString();
            String id = "resume#" + sourceId + "#snapshot";
            results.put(id, new Evidence(id, "resume", sourceId, clip(snapshot.resumeText())));
        }

        // 即使快照已匹配，也执行所请求的资料检索；402/409 不应被一个快照命中掩盖。
        List<KnowledgeChunk.ScoredChunk> candidates = rag.retrieve(query, Math.min(MAX_CANDIDATES, limit * 4));
        for (KnowledgeChunk.ScoredChunk candidate : candidates) {
            if (results.size() >= limit) {
                break;
            }
            KnowledgeChunk chunk = candidate == null ? null : candidate.chunk();
            if (chunk == null || !userId.equals(chunk.userId()) || chunk.sourceType() == null
                    || !MATERIAL_TYPES.contains(chunk.sourceType())
                    || chunk.id() == null || chunk.id().isBlank()
                    || chunk.sourceId() == null || chunk.sourceId().isBlank()
                    || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            results.putIfAbsent(chunk.id(), new Evidence(chunk.id(), chunk.sourceType(), chunk.sourceId(), clip(chunk.content())));
        }
        return List.copyOf(results.values());
    }

    private Long requireUserId() {
        Long userId = currentUser.currentUserId();
        if (userId == null) {
            throw new IllegalStateException("INTERVIEW_USER_MISSING：面试资料访问缺少用户身份");
        }
        return userId;
    }

    private static boolean matchesResume(String resumeText, String query) {
        if (resumeText == null || resumeText.isBlank()) {
            return false;
        }
        String resume = resumeText.toLowerCase(Locale.ROOT);
        String normalizedQuery = query.strip().toLowerCase(Locale.ROOT);
        if (resume.contains(normalizedQuery)) {
            return true;
        }
        Matcher words = QUERY_WORDS.matcher(normalizedQuery);
        while (words.find()) {
            if (resume.contains(words.group())) {
                return true;
            }
        }
        Matcher phrases = HAN_PHRASES.matcher(normalizedQuery);
        while (phrases.find()) {
            String phrase = phrases.group();
            for (int i = 0; i < phrase.length() - 1; i++) {
                if (resume.contains(phrase.substring(i, i + 2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String clip(String value) {
        // 避免在 UTF-16 代理对中间截断，保留内容可正常序列化。
        int end = Math.min(value.length(), MAX_CONTENT_CHARS);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
