package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.Note;
import com.bupt.mockinterview.domain.ProjectDoc;
import com.bupt.mockinterview.domain.RealInterviewRecord;
import com.bupt.mockinterview.domain.ResumeVersion;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.domain.repository.NoteRepository;
import com.bupt.mockinterview.domain.repository.ProjectDocRepository;
import com.bupt.mockinterview.domain.repository.RealInterviewRecordRepository;
import com.bupt.mockinterview.domain.repository.ResumeVersionRepository;
import com.bupt.mockinterview.llm.LlmPort;
import com.bupt.mockinterview.llm.LlmProperties;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资料管理（api.md §2）：JD / 简历 / 笔记 / 项目文档 / 真实面试记录的增删查。
 *
 * 这四类文本同时是 RAG 的语料源（domain.md §2：project_doc 与 resume 双源），
 * 所以写入时同步切块向量化入库——检索侧才问得出「只属于你」的题。
 * RAG 入库失败（如 embedding key 未配）只记日志，不阻断写入。
 */
@RestController
@RequestMapping("/api")
public class MaterialController {

    private static final Logger log = LoggerFactory.getLogger(MaterialController.class);

    private final JdProfileRepository jdRepo;
    private final ResumeVersionRepository resumeRepo;
    private final NoteRepository noteRepo;
    private final ProjectDocRepository docRepo;
    private final RealInterviewRecordRepository recordRepo;
    private final RagService rag;
    private final LlmPort llm;
    private final LlmProperties props;
    private final ObjectMapper mapper;

    public MaterialController(JdProfileRepository jdRepo, ResumeVersionRepository resumeRepo,
                              NoteRepository noteRepo, ProjectDocRepository docRepo,
                              RealInterviewRecordRepository recordRepo, RagService rag,
                              LlmPort llm, LlmProperties props, ObjectMapper mapper) {
        this.jdRepo = jdRepo;
        this.resumeRepo = resumeRepo;
        this.noteRepo = noteRepo;
        this.docRepo = docRepo;
        this.recordRepo = recordRepo;
        this.rag = rag;
        this.llm = llm;
        this.props = props;
        this.mapper = mapper;
    }

    // ---------- JD ----------

    public record JdRequest(String rawText, List<String> rawTexts, String sameTypeTag, String jobType) {}

    /** 上传 JD（单条或批量；同类多 JD 可勾选一起练）。 */
    @PostMapping("/jd")
    public List<Map<String, Object>> createJd(@RequestBody JdRequest req) {
        List<String> texts = new ArrayList<>();
        if (req.rawText() != null && !req.rawText().isBlank()) {
            texts.add(req.rawText());
        }
        if (req.rawTexts() != null) {
            for (String t : req.rawTexts()) {
                if (t != null && !t.isBlank()) {
                    texts.add(t);
                }
            }
        }
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("rawText / rawTexts 不能为空");
        }

        List<Map<String, Object>> resp = new ArrayList<>();
        for (String text : texts) {
            JdProfile jd = new JdProfile();
            jd.setRawText(text);
            jd.setSameTypeTag(req.sameTypeTag());

            // 考点关键词与岗位类型：交给 LLM 抽（非思考 + JSON 模式），失败则留空不影响后续
            Map<String, Object> parsed = parseJd(text);
            jd.setKeywords(json(parsed.get("keywords")));
            String jobType = req.jobType() != null ? req.jobType() : (String) parsed.get("jobType");
            jd.setJobType(jobType);

            JdProfile saved = jdRepo.save(jd);
            resp.add(mapOf("jdId", saved.getId(), "keywords", parsed.get("keywords"),
                    "jobType", saved.getJobType(), "sameTypeTag", saved.getSameTypeTag()));
        }
        return resp;
    }

    @GetMapping("/jd")
    public List<Map<String, Object>> listJd() {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (JdProfile jd : jdRepo.findAll()) {
            resp.add(mapOf("jdId", jd.getId(), "jobType", jd.getJobType(),
                    "sameTypeTag", jd.getSameTypeTag(), "createdAt", str(jd.getCreatedAt())));
        }
        return resp;
    }

    // ---------- 简历 ----------

    public record ResumeRequest(String content) {}

    @PostMapping("/resume")
    public Map<String, Object> createResume(@RequestBody ResumeRequest req) {
        requireText(req.content(), "content");
        ResumeVersion v = new ResumeVersion();
        v.setContent(req.content());
        // 第一份简历自动启用，避免首次使用时还要手动 activate
        v.setActive(resumeRepo.count() == 0);
        ResumeVersion saved = resumeRepo.save(v);
        index("resume", saved.getId(), saved.getContent());
        return mapOf("resumeId", saved.getId(), "createdAt", str(saved.getCreatedAt()), "isActive", saved.isActive());
    }

    @GetMapping("/resume")
    public List<Map<String, Object>> listResume() {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (ResumeVersion v : resumeRepo.findAll()) {
            resp.add(mapOf("resumeId", v.getId(), "createdAt", str(v.getCreatedAt()), "isActive", v.isActive()));
        }
        return resp;
    }

    @PostMapping("/resume/{resumeId}/activate")
    public Map<String, Object> activateResume(@PathVariable Long resumeId) {
        ResumeVersion target = resumeRepo.findById(resumeId)
                .orElseThrow(() -> new IllegalArgumentException("简历版本不存在: " + resumeId));
        for (ResumeVersion v : resumeRepo.findByIsActiveTrue()) {
            if (!v.getId().equals(resumeId)) {
                v.setActive(false);
                resumeRepo.save(v);
            }
        }
        target.setActive(true);
        resumeRepo.save(target);
        return mapOf("resumeId", target.getId(), "isActive", true);
    }

    // ---------- 笔记 ----------

    public record NoteRequest(String content) {}

    @PostMapping("/note")
    public Map<String, Object> createNote(@RequestBody NoteRequest req) {
        requireText(req.content(), "content");
        Note n = new Note();
        n.setContent(req.content());
        Note saved = noteRepo.save(n);
        index("note", saved.getId(), saved.getContent());
        return mapOf("noteId", saved.getId());
    }

    @GetMapping("/note")
    public List<Map<String, Object>> listNote() {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (Note n : noteRepo.findAll()) {
            resp.add(mapOf("noteId", n.getId(), "content", n.getContent(), "createdAt", str(n.getCreatedAt())));
        }
        return resp;
    }

    // ---------- 项目文档（质疑官弹药库）----------

    public record ProjectDocRequest(String title, String content) {}

    @PostMapping("/project-doc")
    public Map<String, Object> createProjectDoc(@RequestBody ProjectDocRequest req) {
        requireText(req.title(), "title");
        requireText(req.content(), "content");
        ProjectDoc d = new ProjectDoc();
        d.setTitle(req.title());
        d.setContent(req.content());
        ProjectDoc saved = docRepo.save(d);
        index("project_doc", saved.getId(), saved.getContent());
        return mapOf("docId", saved.getId());
    }

    @GetMapping("/project-doc")
    public List<Map<String, Object>> listProjectDoc() {
        List<Map<String, Object>> resp = new ArrayList<>();
        for (ProjectDoc d : docRepo.findAll()) {
            resp.add(mapOf("docId", d.getId(), "title", d.getTitle(), "updatedAt", str(d.getUpdatedAt())));
        }
        return resp;
    }

    /** 补充/覆盖「现状」（MVP 为覆盖）。 */
    @PutMapping("/project-doc/{docId}")
    public Map<String, Object> updateProjectDoc(@PathVariable Long docId, @RequestBody ProjectDocRequest req) {
        requireText(req.content(), "content");
        ProjectDoc d = docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("项目文档不存在: " + docId));
        d.setContent(req.content());
        if (req.title() != null && !req.title().isBlank()) {
            d.setTitle(req.title());
        }
        ProjectDoc saved = docRepo.save(d);
        index("project_doc", saved.getId(), saved.getContent());
        return mapOf("docId", saved.getId(), "updatedAt", str(saved.getUpdatedAt()));
    }

    // ---------- 真实面试记录 ----------

    public record RealInterviewRequest(String content) {}

    @PostMapping("/real-interview")
    public Map<String, Object> createRealInterview(@RequestBody RealInterviewRequest req) {
        requireText(req.content(), "content");
        RealInterviewRecord r = new RealInterviewRecord();
        r.setContent(req.content());
        RealInterviewRecord saved = recordRepo.save(r);
        index("real_interview", saved.getId(), saved.getContent());
        return mapOf("recordId", saved.getId());
    }

    // ---------- 内部 ----------

    /** 写入 RAG 语料库；失败只记日志（embedding 未配时不影响主流程）。 */
    private void index(String sourceType, Long id, String text) {
        try {
            rag.ingest(text, sourceType, String.valueOf(id));
        } catch (Exception e) {
            log.warn("RAG 入库失败（{}#{}）：{}", sourceType, id, e.getMessage());
        }
    }

    /** JD 解析：LLM 抽考点关键词 + 岗位类型（JSON 模式），失败降级为空。 */
    private Map<String, Object> parseJd(String rawText) {
        String system = """
                你是招聘 JD 分析助手。请从 JD 中提取：
                1. keywords：考点关键词数组（技术栈、能力要求，8-15 个，短词）
                2. jobType：岗位类型（如「Java 后端」「后端开发」）
                只输出 JSON，不要 markdown 代码块。格式：{"keywords":["..."],"jobType":"..."}
                """;
        try {
            LlmReply reply = llm.chat(props.getModelFlash(), false,
                    List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", rawText)),
                    null, true);
            JsonNode root = mapper.readTree(stripFence(reply.content()));
            List<String> keywords = new ArrayList<>();
            JsonNode arr = root.path("keywords");
            if (arr.isArray()) {
                arr.forEach(n -> keywords.add(n.asText("")));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("keywords", keywords);
            out.put("jobType", root.path("jobType").asText(null));
            return out;
        } catch (Exception e) {
            log.warn("JD 解析失败，keywords 留空：{}", e.getMessage());
            return Map.of("keywords", List.of());
        }
    }

    private static String stripFence(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                t = t.substring(nl + 1, end).trim();
            }
        }
        return t;
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private String json(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 保持字段顺序的 map（响应更稳定、便于断言）。 */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
