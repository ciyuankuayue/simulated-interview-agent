package com.bupt.mockinterview.measurement;

import com.bupt.mockinterview.billing.UsageScene;
import com.bupt.mockinterview.domain.JdProfile;
import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.repository.JdProfileRepository;
import com.bupt.mockinterview.llm.LlmReply;
import com.bupt.mockinterview.llm.LlmService;
import com.bupt.mockinterview.llm.chat.DeepSeekChatProvider;
import com.bupt.mockinterview.llm.chat.ChatResponse;
import com.bupt.mockinterview.llm.chat.ModelRole;
import com.bupt.mockinterview.rag.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Opt-in paid pilot: real loopback HTTP, real model/embedding; isolated H2 and RAM index.
 * Run: .\mvnw.cmd test "-Dtest=InterviewMeasurementTest" "-Dmeasurement.live=true"
 * No production implementation, account, database, index, API or model default is changed.
 */
@EnabledIfSystemProperty(named = "measurement.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:interview_measurement;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "app.auth.jwt-enabled=false", "app.tenant.strict=false",
        "app.rag.ram-directory=true", "app.rag.clean-on-empty-sources=false",
        "app.quota.signup-bonus-tokens=1000000",
        "deepseek.api-key=${DEEPSEEK_API_KEY:}", "dashscope.api-key=${DASHSCOPE_API_KEY:}",
        "deepseek.model-flash=deepseek-flash", "deepseek.model-reasoner=deepseek-flash",
        "deepseek.max-tokens=8192", "deepseek.timeout-ms=60000"
})
class InterviewMeasurementTest {
    record Case(String id, String role, String jobType, String jd, String material, String focus) {}
    static class Sample {
        public String id, caseId, mode, promptSha256, question, error;
        public boolean warmup;
        public int repeat, httpStatus;
        public double apiMs, ragMs, embeddingMs, searchMs, generationMs, providerMs;
        public long promptTokens, completionTokens, totalTokens;
        public List<String> retrievedIds = List.of();
    }
    @Autowired ObjectMapper mapper;
    @Autowired JdProfileRepository jdRepo;
    @Autowired User testUser;
    @LocalServerPort int port;
    @SpyBean LlmService llm;
    @SpyBean RagService rag;
    @SpyBean(name = "embedder") Embedder embedder;
    @SpyBean(name = "vectorStore") VectorStore store;
    @SpyBean DeepSeekChatProvider provider;
    final AtomicReference<Sample> current = new AtomicReference<>();
    final List<Map<String,Object>> embeddingUsage = new ArrayList<>();
    Path output;

    @Test
    void pairedFirstQuestionPilot() throws Exception {
        assertThat(System.getenv("DEEPSEEK_API_KEY")).as("DeepSeek key required, never use fake model").isNotBlank();
        assertThat(System.getenv("DASHSCOPE_API_KEY")).as("DashScope key required, never use fake embedding").isNotBlank();
        output = Path.of("target", "interview-measurement", Instant.now().toString().replace(':','-'));
        Files.createDirectories(output);
        byte[] fixture = Objects.requireNonNull(getClass().getResourceAsStream("/measurement/interview-cases.json")).readAllBytes();
        List<Case> cases = mapper.readValue(fixture, new TypeReference<>() {});
        Files.write(output.resolve("cases.json"), fixture);
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("startedUtc", Instant.now().toString());
        manifest.put("java", System.getProperty("java.version"));
        manifest.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        manifest.put("processors", Runtime.getRuntime().availableProcessors());
        manifest.put("fixtureSha256", sha(fixture));
        manifest.put("model", "deepseek-flash");
        manifest.put("embeddingModel", "qwen3.7-text-embedding-flash");
        manifest.put("maxTokens", 8192);
        manifest.put("design", "6 synthetic cases x 2 repeats x 2 modes; paired interleaved order; 1 warmup per mode");
        manifest.put("endpoint", "real loopback POST /api/interview/start; H2; RAM Lucene; test identity; no browser/WAN/MySQL");
        manifest.put("timing", "nanoTime; providerMs includes network/retries/response parse, not server-only inference; API waits for complete first question");
        manifest.put("qualityRubric", "predeclared 0-2 each: relevance, jobFit, depth, evidenceDiscipline; acceptable=sum>=6 and each>=1; evaluator must disclose identity");
        manifest.put("limits", "pilot, no historical before/after or proven quality non-inferiority; no streaming TTFT; no price estimate without tariff; setup excluded");
        write("manifest.json", manifest);
        installProbes();
        seedReferenceCorpus();
        List<Sample> samples = new ArrayList<>();
        for (int index=0; index<cases.size(); index++) {
            Case c = cases.get(index);
            // Only the current synthetic candidate's statement is visible alongside generic reference text.
            store.deleteBySource(testUser.getId(), "measurement", "candidate");
            rag.ingest(c.material(), "measurement", "candidate");
            JdProfile jd = new JdProfile();
            jd.setUser(testUser); jd.setRawText(c.jd()); jd.setJobType(c.jobType()); jd.setKeywords("[]");
            long jdId = jdRepo.saveAndFlush(jd).getId();
            if (index == 0) {
                samples.add(request(c, jdId, "thinking", 0, true));
                samples.add(request(c, jdId, "fast", 0, true));
            }
            for (int repeat=1; repeat<=2; repeat++) {
                String[] order = (index + repeat) % 2 == 0
                        ? new String[]{"thinking", "fast"} : new String[]{"fast", "thinking"};
                for (String mode : order) samples.add(request(c, jdId, mode, repeat, false));
            }
        }
        write("samples.json", samples);
        write("embedding-usage.json", embeddingUsage);
        Map<String,Object> summary = new LinkedHashMap<>();
        for (String mode : List.of("thinking", "fast")) {
            List<Sample> measured = samples.stream().filter(s -> !s.warmup && mode.equals(s.mode)).toList();
            List<Sample> ok = measured.stream().filter(s -> s.httpStatus == 200 && s.error == null).toList();
            Map<String,Object> stats = new LinkedHashMap<>();
            stats.put("attempted", measured.size()); stats.put("successful", ok.size());
            stats.put("apiMs", stats(ok.stream().map(s->s.apiMs).toList()));
            stats.put("ragMs", stats(ok.stream().map(s->s.ragMs).toList()));
            stats.put("embeddingMs", stats(ok.stream().map(s->s.embeddingMs).toList()));
            stats.put("searchMs", stats(ok.stream().map(s->s.searchMs).toList()));
            stats.put("generationMs", stats(ok.stream().map(s->s.generationMs).toList()));
            stats.put("providerMs", stats(ok.stream().map(s->s.providerMs).toList()));
            stats.put("reportedChatTotalTokens", ok.stream().mapToLong(s->s.totalTokens).sum());
            summary.put(mode, stats);
        }
        write("summary.json", summary);
        List<Map<String,Object>> blind = new ArrayList<>();
        List<Sample> measured = new ArrayList<>(samples.stream().filter(s -> !s.warmup).toList());
        Collections.shuffle(measured, new Random(20261020));
        List<Map<String,String>> key = new ArrayList<>();
        for(int i=0;i<measured.size();i++) {
            Sample s = measured.get(i); Case c=cases.stream().filter(x->x.id().equals(s.caseId)).findFirst().orElseThrow();
            String blindId=String.format("Q%02d", i+1);
            blind.add(Map.of("blindId",blindId,"case",c,"question",s.question==null?"":s.question,"httpStatus",s.httpStatus));
            key.add(Map.of("blindId",blindId,"sampleId",s.id,"mode",s.mode));
        }
        write("quality-blind.json", blind); write("quality-key.json", key);
        System.out.println("[MEASUREMENT] artifacts=" + output.toAbsolutePath());
        assertThat(samples.stream().filter(s -> !s.warmup)).hasSize(24);
        // Failed real calls remain in artifacts; do not silently remove or replace them.
        assertThat(samples).allMatch(s -> s.httpStatus==200 && s.error==null, "all requests generated nonempty questions");
        for(Case c:cases) {
            assertThat(samples.stream().filter(s->!s.warmup && c.id().equals(s.caseId)).map(s->s.promptSha256).distinct())
                    .as("identical prompt within each paired case").hasSize(1);
        }
    }

    private void installProbes() throws Exception {
        doAnswer(inv -> {
            long t=System.nanoTime();
            try {
                Embedding e=(Embedding)inv.callRealMethod();
                embeddingUsage.add(Map.of("tokens",e.tokens(),"estimated",e.estimated(),"sampleId",current.get()==null?"setup":current.get().id));
                return e;
            } finally { if(current.get()!=null) current.get().embeddingMs+=ms(t); }
        }).when(embedder).embed(anyString());
        doAnswer(inv -> {
            long t=System.nanoTime();
            try { return inv.callRealMethod(); }
            finally { if(current.get()!=null) current.get().searchMs+=ms(t); }
        }).when(store).search(anyLong(), any(float[].class), anyInt());
        doAnswer(inv -> {
            long t=System.nanoTime();
            try {
                @SuppressWarnings("unchecked") var hits=(List<KnowledgeChunk.ScoredChunk>)inv.callRealMethod();
                if(current.get()!=null) current.get().retrievedIds=hits.stream().map(h->h.chunk().id()).toList();
                return hits;
            } finally { if(current.get()!=null) current.get().ragMs+=ms(t); }
        }).when(rag).retrieve(anyString(),anyInt());
        doAnswer(inv -> {
            long t=System.nanoTime();
            try { return inv.callRealMethod(); }
            finally { if(current.get()!=null) current.get().providerMs+=ms(t); }
        }).when(provider).chat(any(),anyString());
        doAnswer(inv -> {
            Sample s=current.get();
            if(s==null) return inv.callRealMethod();
            String system=inv.getArgument(0), user=inv.getArgument(1);
            s.promptSha256=sha((system+"\n"+user).getBytes(StandardCharsets.UTF_8));
            long t=System.nanoTime();
            try {
                // Test-only substitution of the mode, same prompt/model/token cap and production billing path.
                LlmReply reply = "thinking".equals(s.mode)
                        ? llm.chat(ModelRole.REASONER,true,List.of(LlmService.sys(system),LlmService.userMsg(user)),null,false,8192,UsageScene.FOLLOWUP)
                        : (LlmReply)inv.callRealMethod();
                assertThat(reply.content()).isNotBlank();
                s.question=reply.content();
                if(reply.usage()!=null) {
                    s.promptTokens=reply.usage().promptTokens();s.completionTokens=reply.usage().completionTokens();s.totalTokens=reply.usage().totalTokens();
                }
                return reply;
            } finally { s.generationMs=ms(t); }
        }).when(llm).followUp(anyString(),anyString());
    }

    private void seedReferenceCorpus() throws Exception {
        String corpus = new String(Objects.requireNonNull(getClass().getResourceAsStream("/corpus/java-backend-knowledge.md")).readAllBytes(),StandardCharsets.UTF_8);
        int count=0;
        for(String line:corpus.split("\\R")) {
            if(line.startsWith("#") || line.trim().length()<25) continue;
            rag.ingest(line.trim(),"measurement-reference","ref-"+count++);
        }
        write("corpus.json",Map.of("referenceSources",count,"chunksBeforeCandidate",store.size(),"corpusSha256",sha(corpus.getBytes(StandardCharsets.UTF_8)),"directory","ByteBuffersDirectory; setup and ingest timing excluded"));
    }

    private Sample request(Case c,long jdId,String mode,int repeat,boolean warmup) throws Exception {
        Sample s=new Sample();s.id=c.id()+"-"+repeat+"-"+mode;s.caseId=c.id();s.mode=mode;s.repeat=repeat;s.warmup=warmup;
        current.set(s);
        OkHttpClient local=new OkHttpClient.Builder().readTimeout(240,TimeUnit.SECONDS).callTimeout(250,TimeUnit.SECONDS).build();
        String body=mapper.writeValueAsString(Map.of("jdId",jdId,"roles",List.of(c.role()),"maxQuestions",1));
        long t=System.nanoTime();
        try(Response response=local.newCall(new Request.Builder().url("http://127.0.0.1:"+port+"/api/interview/start")
                .post(RequestBody.create(body,MediaType.get("application/json"))).build()).execute()) {
            s.httpStatus=response.code();
            String content=response.body()==null?"":response.body().string();
            if(response.isSuccessful()) s.question=mapper.readTree(content).path("firstQuestion").asText();
            else s.error="HTTP "+response.code()+"; inspect application log (no response/key dump)";
            if(s.question==null || s.question.isBlank()) s.error="empty question or HTTP error";
        } catch(Exception e) { s.error=e.getClass().getSimpleName(); }
        finally { s.apiMs=ms(t); current.set(null); }
        Files.writeString(output.resolve("samples.jsonl"),mapper.writeValueAsString(s)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.out.printf(Locale.ROOT,"[MEASUREMENT] %s warmup=%s status=%d api=%.1fms rag=%.3fms model=%.1fms tokens=%d%n",s.id,s.warmup,s.httpStatus,s.apiMs,s.ragMs,s.providerMs,s.totalTokens);
        return s;
    }
    private static double ms(long start) { return (System.nanoTime()-start)/1_000_000.0; }
    private static Map<String,Object> stats(List<Double> xs) {
        if(xs.isEmpty()) return Map.of("n",0);
        List<Double> sorted=xs.stream().sorted().toList();
        return Map.of("n",xs.size(),"mean",xs.stream().mapToDouble(x->x).average().orElseThrow(),
                "p50",sorted.get((int)Math.ceil(xs.size()*0.5)-1),"p95",sorted.get((int)Math.ceil(xs.size()*0.95)-1),"max",sorted.get(sorted.size()-1));
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private void write(String name,Object value) throws Exception { mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name).toFile(),value); }
}
