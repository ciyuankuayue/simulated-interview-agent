package com.bupt.mockinterview.memory;

import com.bupt.mockinterview.domain.WeakPoint;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.ScoreResult;
import com.bupt.mockinterview.llm.ScoreTrack;
import com.bupt.mockinterview.llm.chat.ModelRole;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F3 错题本复习模式：把「知识点」变成一道可作答的题，用户答完判分并更新复习阶梯。
 *
 * <p>三步交互（前端驱动）：
 * <ol>
 *   <li>{@link #generateQuestion(WeakPoint)} —— LLM 针对该知识点出一道简答题</li>
 *   <li>前端展示题目，用户输入回答</li>
 *   <li>{@link #reviewAnswer(WeakPoint, String)} —— LLM 判分（0-8）+ 更新阶梯，返回得分与讲解</li>
 * </ol>
 *
 * <p>判分复用 {@link LlmPort#score} 的四维 rubric；阶梯更新委托 {@link WeakPointMemoryService#reviewAnswer}。
 */
@Service
public class WeakPointReviewService {

    private static final String QUESTION_SYSTEM =
            "你是技术面试官。用户正在复习一个薄弱知识点，请针对这个知识点出一道简答题，" +
            "要求：1) 题目只考这个知识点本身，不要扩展到无关领域；2) 一句话，不要给出任何提示或答案；" +
            "3) 直接输出题目，不要前缀、编号或 markdown。知识点：%s";

    private final LlmPort llm;
    private final WeakPointMemoryService memory;

    public WeakPointReviewService(LlmPort llm, WeakPointMemoryService memory) {
        this.llm = llm;
        this.memory = memory;
    }

    /** 针对知识点生成一道复习题（非思考档，快）。失败抛 LlmException，由全局异常转 504。 */
    public String generateQuestion(WeakPoint w) {
        String system = String.format(QUESTION_SYSTEM, w.getKnowledgePoint());
        return llm.followUp(system, "请出题。").content();
    }

    /**
     * 用户作答后判分并更新复习阶梯。
     *
     * @param question 上一步生成的复习题（判分需要题目才能评估答案是否切题）
     * @param answer   用户作答
     * @return 包含 score / explanation / missedPoints / newStage / resolved / nextReviewAt 的 Map
     */
    public Map<String, Object> reviewAnswer(WeakPoint w, String question, String answer) {
        ScoreResult sr = llm.score(ScoreTrack.TECH, question, answer);
        int score = sr.score();
        WeakPoint updated = memory.reviewAnswer(w.getId(), score, Instant.now());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("score", score);
        resp.put("explanation", sr.missedPoints() == null || sr.missedPoints().isEmpty()
                ? "回答正确，掌握得不错。" : String.join("；", sr.missedPoints()));
        resp.put("missedPoints", sr.missedPoints());
        resp.put("newStage", updated.getReviewStage());
        resp.put("resolved", updated.getResolved());
        resp.put("nextReviewAt", updated.getNextReviewAt() == null ? null : updated.getNextReviewAt().toString());
        return resp;
    }

    /** 纯透传，给控制器用。 */
    public WeakPoint require(Long id) {
        return memory.require(id);
    }

    /** 取到期列表（复习模式入口用）。 */
    public List<WeakPoint> due(Instant now) {
        return memory.due(now);
    }
}
