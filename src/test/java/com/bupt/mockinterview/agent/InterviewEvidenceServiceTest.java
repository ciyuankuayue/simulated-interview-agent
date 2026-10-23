package com.bupt.mockinterview.agent;

import com.bupt.mockinterview.agent.InterviewEvidenceService.Evidence;
import com.bupt.mockinterview.agent.InterviewEvidenceService.Snapshot;
import com.bupt.mockinterview.billing.QuotaExhaustedException;
import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.ResumeVersion;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.ResumeVersionRepository;
import com.bupt.mockinterview.rag.KnowledgeChunk;
import com.bupt.mockinterview.rag.RagService;
import com.bupt.mockinterview.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 离线验证证据的身份、简历版本、内容预算与异常传播，不调用模型/embedding 服务。 */
class InterviewEvidenceServiceTest {

    private final ResumeVersionRepository resumes = mock(ResumeVersionRepository.class);
    private final RagService rag = mock(RagService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final InterviewEvidenceService service = new InterviewEvidenceService(resumes, rag, currentUser);

    @BeforeEach
    void identifyUser() {
        when(currentUser.currentUserId()).thenReturn(7L);
    }

    @Test
    void snapshotCapturesOriginalTextAndSelectedVersion() {
        JdProfile jd = jd(7L);
        jd.setRawText("  JD 原文\nRedis  ");
        ResumeVersion resume = resume(11L, 7L, "  所选简历\n原文  ");
        when(resumes.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(resume));

        Snapshot snapshot = service.snapshot(jd, 11L);
        jd.setRawText("场中改写");
        resume.setContent("场中改写");

        assertThat(snapshot).isEqualTo(new Snapshot("  JD 原文\nRedis  ", 11L, "  所选简历\n原文  "));
        verify(resumes).findByIdAndUserId(11L, 7L);
    }

    @Test
    void noSelectedResumeDoesNotImplicitlyReadAnyVersion() {
        assertThat(service.snapshot(jd(7L), null).resumeText()).isEmpty();
        verifyNoInteractions(resumes);
    }

    @Test
    void rejectsForeignOrMissingResumeAndForeignJd() {
        when(resumes.findByIdAndUserId(12L, 7L)).thenReturn(Optional.empty());
        when(resumes.findByIdAndUserId(13L, 7L)).thenReturn(Optional.of(resume(13L, 8L, "其他用户")));

        assertThatThrownBy(() -> service.snapshot(jd(7L), 12L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(jd(7L), 13L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(jd(8L), null)).isInstanceOf(IllegalArgumentException.class);
        verify(resumes, never()).findByIdAndUserId(13L, 8L);
    }

    @Test
    void searchUsesOnlySelectedResumeSnapshotAndAllowedOwnMaterials() throws Exception {
        Snapshot snapshot = new Snapshot("岗位", 11L, "新版简历：本人负责 Redis 缓存改造");
        when(rag.retrieve("Redis 缓存", 20)).thenReturn(List.of(
                chunk("resume#10#0", 7L, "resume", "10", "旧版本简历：未选择"),
                chunk("resume#11#0", 7L, "resume", "11", "已选择但场中改写的简历"),
                chunk("note#1#0", 8L, "note", "1", "其他用户的秘密"),
                chunk("note#2#0", null, "note", "2", "匿名实验块"),
                chunk("project_doc#3#0", 7L, "project_doc", "3", "本人项目的缓存方案"),
                chunk("note#4#0", 7L, "note", "4", "缓存笔记"),
                chunk("real_interview#5#0", 7L, "real_interview", "5", "真实面试回忆"),
                chunk("jd#6#0", 7L, "jd", "6", "其他 JD")));

        List<Evidence> evidence = service.search(snapshot, "Redis 缓存", 5);

        assertThat(evidence).extracting(Evidence::id).containsExactly(
                "resume#11#snapshot", "project_doc#3#0", "note#4#0", "real_interview#5#0");
        assertThat(evidence.get(0).content()).isEqualTo(snapshot.resumeText());
        assertThat(evidence).allSatisfy(e -> assertThat(e.content()).doesNotContain("旧版本", "场中改写", "秘密", "实验"));
    }

    @Test
    void noSelectedResumeNeverIncludesVectorResumeChunks() throws Exception {
        when(rag.retrieve("Redis", 8)).thenReturn(List.of(chunk("resume#11#0", 7L, "resume", "11", "Redis 简历")));

        assertThat(service.search(new Snapshot("岗位", null, ""), "Redis", 2)).isEmpty();
    }

    @Test
    void ignoresMalformedAndBlankChunks() throws Exception {
        when(rag.retrieve("缓存", 20)).thenReturn(List.of(
                chunk("broken", 7L, null, "1", "不可识别类型"),
                chunk("note#1#0", 7L, "note", "1", "  "),
                chunk("note#2#0", 7L, "note", null, "缺来源"),
                chunk("", 7L, "note", "3", "缺证据标识"),
                chunk("note#4#0", 7L, "note", "4", "有效笔记")));

        assertThat(service.search(new Snapshot("", null, ""), "缓存", 5))
                .extracting(Evidence::id).containsExactly("note#4#0");
    }

    @Test
    void capsCandidateCountResultCountAndContentAndDeduplicates() throws Exception {
        List<KnowledgeChunk.ScoredChunk> candidates = IntStream.range(0, 20)
                .mapToObj(i -> chunk("note#" + (i / 2) + "#0", 7L, "note", "" + (i / 2), "字".repeat(2000))).toList();
        when(rag.retrieve("缓存", 20)).thenReturn(candidates);

        List<Evidence> result = service.search(new Snapshot("", null, ""), "缓存", Integer.MAX_VALUE);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(Evidence::id).doesNotHaveDuplicates();
        assertThat(result).allSatisfy(e -> assertThat(e.content()).hasSize(1500));
        verify(rag).retrieve("缓存", 20);
    }

    @Test
    void nonpositiveLimitAndBlankQueryDoNotEmbed() throws Exception {
        Snapshot snapshot = new Snapshot("", null, "");
        assertThat(service.search(snapshot, "缓存", 0)).isEmpty();
        assertThat(service.search(snapshot, "缓存", Integer.MIN_VALUE)).isEmpty();
        assertThat(service.search(snapshot, "  ", 5)).isEmpty();
        assertThat(service.search(snapshot, null, 5)).isEmpty();
        verifyNoInteractions(rag);
    }

    @Test
    void missingIdentityFailsBeforeAnyRepositoryOrRagAccess() {
        when(currentUser.currentUserId()).thenReturn(null);

        assertThatThrownBy(() -> service.snapshot(jd(7L), 11L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERVIEW_USER_MISSING");
        assertThatThrownBy(() -> service.search(new Snapshot("", null, ""), "缓存", 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERVIEW_USER_MISSING");
        verifyNoInteractions(resumes, rag);
    }

    @Test
    void quotaAndConflictAndOrdinaryFailurePropagateUnchangedEvenWithResumeMatch() throws Exception {
        Snapshot snapshot = new Snapshot("", 11L, "Redis 项目");
        QuotaExhaustedException exhausted = new QuotaExhaustedException("额度耗尽");
        IllegalStateException conflict = new IllegalStateException("并发冲突");
        IOException unavailable = new IOException("向量检索失败");
        when(rag.retrieve(anyString(), anyInt())).thenThrow(exhausted, conflict, unavailable);

        assertThatThrownBy(() -> service.search(snapshot, "Redis", 1)).isSameAs(exhausted);
        assertThatThrownBy(() -> service.search(snapshot, "Redis", 1)).isSameAs(conflict);
        assertThatThrownBy(() -> service.search(snapshot, "Redis", 1)).isSameAs(unavailable);
    }

    private static JdProfile jd(Long userId) {
        JdProfile jd = new JdProfile();
        jd.setUser(user(userId));
        jd.setRawText("岗位原文");
        return jd;
    }

    private static ResumeVersion resume(Long id, Long userId, String text) {
        ResumeVersion resume = new ResumeVersion();
        resume.setId(id);
        resume.setUser(user(userId));
        resume.setContent(text);
        return resume;
    }

    private static User user(Long id) {
        User user = new User("user-" + id, "unused");
        user.setId(id);
        return user;
    }

    private static KnowledgeChunk.ScoredChunk chunk(String id, Long userId, String sourceType, String sourceId, String text) {
        return new KnowledgeChunk.ScoredChunk(new KnowledgeChunk(id, userId, sourceType, sourceId, text, new float[0]), 1.0);
    }
}
