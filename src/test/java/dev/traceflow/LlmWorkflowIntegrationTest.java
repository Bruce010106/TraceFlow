package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"traceflow.ai.mode=llm","traceflow.ai.api-key=integration-test-only",
    "traceflow.queue.enabled=false","traceflow.ai.model=wire-test","traceflow.ai.response-format=json_object","traceflow.ai.timeout-seconds=15"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class LlmWorkflowIntegrationTest extends AuthenticatedTestSupport {
    record Reply(int status,String body,CountDownLatch entered,CountDownLatch release){}
    static final ObjectMapper WIRE_JSON=new ObjectMapper();
    static final BlockingQueue<Reply> REPLIES=new LinkedBlockingQueue<>();
    static final List<JsonNode> REQUESTS=new CopyOnWriteArrayList<>();
    static final AtomicInteger CALLS=new AtomicInteger();
    static final ExecutorService HTTP_POOL=Executors.newCachedThreadPool();
    static final HttpServer HTTP=startHttp();
    static final EmbeddedPostgres PG=TestDatabase.pg();
    static HttpServer startHttp(){
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(HTTP_POOL);
            server.createContext("/chat/completions",exchange->{
                CALLS.incrementAndGet();
                try {
                    REQUESTS.add(WIRE_JSON.readTree(exchange.getRequestBody()));
                    var reply=REPLIES.poll(5,TimeUnit.SECONDS);
                    if(reply==null)throw new IllegalStateException("Test response missing");
                    if(reply.entered()!=null)reply.entered().countDown();
                    if(reply.release()!=null&&!reply.release().await(12,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timeout");
                    byte[] data=reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(),data.length);exchange.getResponseBody().write(data);
                } catch(Exception e){
                    try {byte[] data="test-provider-error".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,data.length);exchange.getResponseBody().write(data);}
                    catch(Exception ignored){}
                } finally {exchange.close();}
            });server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static EmbeddedPostgres startPostgres(){
        try{return EmbeddedPostgres.builder().setPort(0).setPGStartupWait(Duration.ofSeconds(120))
            .setServerConfig("max_connections","20").setServerConfig("shared_buffers","16MB").setLocaleConfig("locale","C")
            .setConnectConfig("sslmode","disable").setConnectConfig("gssEncMode","disable").start();}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres")+"&sslmode=disable&gssEncMode=disable");
        registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
        registry.add("traceflow.ai.base-url",()->"http://127.0.0.1:"+HTTP.getAddress().getPort());
    }
    @AfterAll static void stop()throws Exception{HTTP.stop(0);HTTP_POOL.shutdownNow();}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired TraceFlowService service;
 @Autowired ExtractionQueue queue;
    @BeforeEach void clean(){
        REPLIES.clear();REQUESTS.clear();CALLS.set(0);
        db.execute("TRUNCATE task_change,change_proposal,extraction_job,source_document,task,project CASCADE");mvc=authenticatedMvc();
    }
    JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,Object body,int status)throws Exception{
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
          JsonNode received=json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());restoreIdentity();
  if(status==202&&"QUEUED".equals(received.path("jobs").get(received.path("jobs").size()-1).path("status").asText())){
   var lease=queue.claim("regression-driver").orElseThrow();queue.execute(lease);
   var refreshed=send(get("/api/documents/"+received.path("id").asText()),null,200);
   ((com.fasterxml.jackson.databind.node.ObjectNode)refreshed).set("duplicate",received.path("duplicate"));return refreshed;
  }
  return received;
    }
    String project()throws Exception{return send(post("/api/projects"),Map.of("name","P3","timezone","Asia/Hong_Kong"),200).path("id").asText();}
    JsonNode document(String project,String content)throws Exception{
        return send(post("/api/projects/"+project+"/documents"),Map.of("title","真实协议测试","content",content,"referenceDate","2026-11-11"),202);
    }
    ObjectNode candidate(String title,String quote){
        var item=json.createObjectNode();item.put("operation","CREATE_TASK");item.putNull("targetTaskId");item.put("title",title);
        item.put("assigneeName","李明");item.put("dueDate","2026-11-13");item.putArray("changedFields").add("title").add("assigneeName").add("dueDate");
        item.putObject("evidence").put("line",1).put("quote",quote);return item;
    }
    String response(ObjectNode...items)throws Exception{
        var output=json.createObjectNode();var array=output.putArray("proposals");for(var item:items)array.add(item);
        return json.writeValueAsString(Map.of("id","p3-wire-response","model","wire-resolved","usage",Map.of("prompt_tokens",123,"completion_tokens",45),
            "choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(output))))));
    }
    void reply(ObjectNode...items)throws Exception{REPLIES.add(new Reply(200,response(items),null,null));}
    JsonNode job(JsonNode document){var jobs=document.path("jobs");return jobs.get(jobs.size()-1);}
    String proposal(JsonNode document){return document.path("proposals").get(0).path("id").asText();}
    Map<String,Object> approval(Long version,String due){
        var map=new HashMap<String,Object>();map.put("title","接口联调");map.put("assigneeName","王敏");map.put("dueDate",due);
        if(version!=null)map.put("expectedTaskVersion",version);return map;
    }
    int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    String fixtureTask(String project){
        UUID id=UUID.randomUUID();db.update("INSERT INTO task(id,project_id,title,assignee_name,due_date) VALUES(?,?,?,?,?)",
            id,UUID.fromString(project),"接口联调","王敏",LocalDate.of(2026,11,13));return id.toString();
    }

    @Test void fullModelProtocolPreservesReviewReplayAndConflict()throws Exception{
        String project=project(),text="请李明在2026-11-13前完成接口联调。";reply(candidate("接口联调",text));
        var source=document(project,text);assertEquals(0,count("task"));assertEquals("PENDING",source.path("proposals").get(0).path("status").asText());
        assertEquals("LLM",source.path("proposals").get(0).path("evidence_json").path("source").asText());
        assertEquals("wire-resolved",job(source).path("resolved_model").asText());assertEquals(123,job(source).path("input_tokens").asInt());
        var created=send(post("/api/proposals/"+proposal(source)+"/approve"),approval(null,"2026-11-13"),200);
        send(post("/api/proposals/"+proposal(source)+"/approve"),approval(null,"2026-11-13"),200);
        assertTrue(document(project,text).path("duplicate").asBoolean());assertEquals(1,CALLS.get());assertEquals(1,count("task_change"));
        String id=created.path("id").asText(),change="把接口联调延期到2026-11-15。";
        var update=candidate("unused",change);update.put("operation","UPDATE_TASK");update.put("targetTaskId",id);
        update.putNull("title");update.putNull("assigneeName");update.put("dueDate","2026-11-15");update.putArray("changedFields").add("dueDate");
        reply(update);var next=document(project,change);
        assertEquals("王敏",next.path("proposals").get(0).path("candidate_json").path("assigneeName").asText());
        var edit=approval(0L,"2026-11-16");edit.put("status","IN_PROGRESS");send(patch("/api/tasks/"+id),edit,200);
        assertEquals("VERSION_CONFLICT",send(post("/api/proposals/"+proposal(next)+"/approve"),approval(0L,"2026-11-15"),409).path("code").asText());
        assertEquals("PENDING",service.document(UUID.fromString(next.path("id").asText())).get("proposals") instanceof List<?> list?
            ((Map<?,?>)list.get(0)).get("status"):null);
        assertEquals(2,send(post("/api/proposals/"+proposal(next)+"/approve"),approval(1L,"2026-11-15"),200).path("version").asInt());
        send(post("/api/proposals/"+proposal(next)+"/approve"),approval(1L,"2026-11-15"),200);
        assertEquals(3,count("task_change"));assertEquals(2,CALLS.get());
    }
    @Test void concurrentDuplicateImportMakesOnlyOneProviderRequest()throws Exception{
        String project=project(),text="请李明完成接口联调。";var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        REPLIES.add(new Reply(200,response(candidate("接口联调",text)),entered,release));
        var initial=service.importDocument(UUID.fromString(project),new Contracts.DocumentInput("纪要",text,LocalDate.of(2026,11,11)));
        assertEquals("QUEUED",((Map<?,?>)((List<?>)initial.get("jobs")).get(0)).get("status"));assertEquals(0,CALLS.get());
        var lease=queue.claim("concurrent-driver").orElseThrow();var pool=Executors.newSingleThreadExecutor();
        try{
            var first=pool.submit(()->{queue.execute(lease);return service.document(lease.documentId());});
            assertTrue(entered.await(8,TimeUnit.SECONDS));
            var duplicate=document(project,text);assertTrue(duplicate.path("duplicate").asBoolean());
            assertEquals("RUNNING",job(duplicate).path("status").asText());assertEquals(1,count("source_document"));
            release.countDown();assertNotNull(first.get(8,TimeUnit.SECONDS));
            assertEquals(1,CALLS.get());assertEquals(1,count("extraction_job"));assertEquals(1,count("change_proposal"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void networkWaitDoesNotLockProjectAndKeepsSnapshotVersion()throws Exception{
        String project=project(),id=fixtureTask(project),text="把接口联调延期到2026-11-15。";
        var item=candidate("unused",text);item.put("operation","UPDATE_TASK");item.put("targetTaskId",id);
        item.putNull("title");item.putNull("assigneeName");item.put("dueDate","2026-11-15");item.putArray("changedFields").add("dueDate");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        REPLIES.add(new Reply(200,response(item),entered,release));var pool=Executors.newFixedThreadPool(2);
        var initial=service.importDocument(UUID.fromString(project),new Contracts.DocumentInput("变更",text,LocalDate.of(2026,11,11)));
        var lease=queue.claim("snapshot-driver").orElseThrow();
        try{
            var extraction=pool.submit(()->{queue.execute(lease);return service.document(lease.documentId());});
            assertTrue(entered.await(8,TimeUnit.SECONDS));
            var edit=pool.submit(()->service.editTask(UUID.fromString(id),new Contracts.TaskInput("接口联调","王敏",LocalDate.of(2026,11,16),Contracts.TaskStatus.IN_PROGRESS,0L)));
            assertEquals(1,((Number)edit.get(4,TimeUnit.SECONDS).get("version")).intValue());
            release.countDown();var source=json.valueToTree(extraction.get(8,TimeUnit.SECONDS));
            assertEquals(0,source.path("proposals").get(0).path("base_task_version").asInt());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void invalidBatchRetainsSourceAndRetriesOnlyOnExplicitRequest()throws Exception{
        String project=project(),text="请李明完成接口联调。";
        var invalid=candidate("整理文档",text);invalid.withObject("/evidence").put("quote","不存在的证据");
        reply(candidate("接口联调",text),invalid);var source=document(project,text);
        assertEquals("FAILED",job(source).path("status").asText());assertEquals("MODEL_INVALID_EVIDENCE",job(source).path("error_code").asText());
        assertEquals(0,count("change_proposal"));assertEquals(0,count("task"));
        document(project,text);assertEquals(1,CALLS.get());
        reply(candidate("接口联调",text));
        var retried=send(post("/api/documents/"+source.path("id").asText()+"/extract"),null,202);
        assertEquals(2,retried.path("jobs").size());assertEquals(2,job(retried).path("generation").asInt());assertEquals(1,job(retried).path("attempt_count").asInt());
        assertEquals("SUCCEEDED",job(retried).path("status").asText());assertEquals(1,count("change_proposal"));
        send(post("/api/documents/"+source.path("id").asText()+"/extract"),null,202);
        assertEquals(2,CALLS.get());assertEquals(2,count("extraction_job"));
    }
    @Test void crossProjectTargetNeverEntersProposalsOrContext()throws Exception{
        String first=project(),other=project(),id=fixtureTask(first),text="把接口联调延期到2026-11-15。";
        var item=candidate("接口联调",text);item.put("operation","UPDATE_TASK");item.put("targetTaskId",id);reply(item);
        var source=document(other,text);assertEquals("MODEL_INVALID_TARGET",job(source).path("error_code").asText());
        assertEquals(0,count("change_proposal"));assertFalse(REQUESTS.get(0).toString().contains(id));
    }
    @Test void proposalPersistenceFailureRollsBackWholeBatch()throws Exception{
        String project=project(),text="请李明完成接口联调，并整理文档。";
        db.execute("CREATE FUNCTION test_proposal_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ordinal=1 THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER test_proposal_failure BEFORE INSERT ON change_proposal FOR EACH ROW EXECUTE FUNCTION test_proposal_failure()");
        try{
            reply(candidate("接口联调",text),candidate("整理文档",text));var source=document(project,text);
            assertEquals("RETRY_WAIT",job(source).path("status").asText());assertEquals("QUEUE_STORAGE_ERROR",job(source).path("error_code").asText());
            assertEquals(0,count("change_proposal"));assertEquals(0,count("task"));assertEquals(1,count("source_document"));
        }finally{db.execute("DROP TRIGGER test_proposal_failure ON change_proposal");db.execute("DROP FUNCTION test_proposal_failure()");}
    }
    @Test void providerFailureIsSanitizedAndDocumentSurvives()throws Exception{
        String project=project();REPLIES.add(new Reply(429,"private-provider-detail integration-test-only",null,null));
        var source=document(project,"请李明整理接口文档。");
        assertEquals("MODEL_RATE_LIMITED",job(source).path("error_code").asText());
        assertFalse(source.toString().contains("private-provider-detail"));assertFalse(source.toString().contains("integration-test-only"));
        assertEquals(0,count("change_proposal"));assertEquals(1,count("source_document"));
    }
    @Test void successfulZeroProposalDocumentIsNotAFailure()throws Exception{
        String project=project();reply();var source=document(project,"今天仅讨论了整体规划，没有确定行动项。");
        assertEquals("SUCCEEDED",job(source).path("status").asText());assertTrue(source.path("proposals").isEmpty());
        assertEquals(0,count("task"));assertEquals(0,count("task_change"));
    }
}
