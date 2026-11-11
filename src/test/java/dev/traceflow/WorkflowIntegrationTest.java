package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"traceflow.ai.mode=mock","traceflow.queue.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
 static final EmbeddedPostgres PG=TestDatabase.pg();
 static EmbeddedPostgres start() {
  try { return EmbeddedPostgres.builder().setPort(0).setPGStartupWait(java.time.Duration.ofSeconds(120)).setServerConfig("max_connections","20").setServerConfig("shared_buffers","16MB").setLocaleConfig("locale","C").setConnectConfig("sslmode","disable").setConnectConfig("gssEncMode","disable").start(); }
  catch(Exception e) { throw new ExceptionInInitializerError(e); }
 }
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
  registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres")+"&sslmode=disable&gssEncMode=disable");
  registry.add("spring.datasource.username",()->"postgres");
  registry.add("spring.datasource.password",()->"");
 }

 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate db;
 @Autowired TraceFlowService service;
 @Autowired ExtractionQueue queue;
 @BeforeEach void clean() { db.execute("TRUNCATE task_change,change_proposal,extraction_job,source_document,task,project CASCADE"); }
 JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,Object body,int status) throws Exception {
  if(body!=null) request.contentType("application/json").content(json.writeValueAsString(body));
    JsonNode received=json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
  if(status==202&&"QUEUED".equals(received.path("jobs").get(received.path("jobs").size()-1).path("status").asText())){
   var lease=queue.claim("regression-driver").orElseThrow();queue.execute(lease);
   var refreshed=send(get("/api/documents/"+received.path("id").asText()),null,200);
   ((com.fasterxml.jackson.databind.node.ObjectNode)refreshed).set("duplicate",received.path("duplicate"));return refreshed;
  }
  return received;
 }
 String project() throws Exception {
  return send(post("/api/projects"),Map.of("name","Example","timezone","Asia/Hong_Kong"),200).get("id").asText();
 }
 JsonNode document(String project,String text) throws Exception {
  return send(post("/api/projects/"+project+"/documents"),Map.of("title","纪要","content",text,"referenceDate","2026-11-10"),202);
 }
 Map<String,Object> approval(Long version) {
  var body=new HashMap<String,Object>();
  body.put("title","接口联调"); body.put("assigneeName","李明"); body.put("dueDate","2026-11-15");
  if(version!=null) body.put("expectedTaskVersion",version);
  return body;
 }
 String proposal(JsonNode document) { return document.get("proposals").get(0).get("id").asText(); }
 JsonNode createTask(String project) throws Exception {
  var document=document(project,"新增任务：接口联调；负责人：李明；截止：2026-11-12");
  var body=approval(null);body.put("dueDate","2026-11-12");
  return send(post("/api/proposals/"+proposal(document)+"/approve"),body,200);
 }
 int count(String table) { return db.queryForObject("SELECT count(*) FROM "+table,Integer.class); }

 @Test void roundTripPreservesEvidenceAndHistory() throws Exception {
  String project=project();var task=createTask(project);String taskId=task.get("id").asText();
  assertEquals(0,task.get("version").asInt());
  var update=document(project,"更新任务："+taskId+"；负责人：李明；截止：2026-11-15");
  assertEquals(0,update.get("proposals").get(0).get("base_task_version").asInt());
  var changed=send(post("/api/proposals/"+proposal(update)+"/approve"),approval(0L),200);
  assertEquals(1,changed.get("version").asInt());
  assertEquals("2026-11-15",changed.get("due_date").asText());
  var history=send(get("/api/tasks/"+taskId+"/history"),null,200);
  assertEquals(2,history.size());assertTrue(history.get(1).get("evidence_json").get("quote").asText().contains("2026-11-15"));
  assertEquals("2026-11-12",history.get(1).get("before_json").get("due_date").asText());
  assertEquals(update.get("id").asText(),history.get(1).get("document_id").asText());
 }
 @Test void duplicateImportAndApprovalHaveOneEffect() throws Exception {
  String project=project();String text="新增任务：接口联调；负责人：李明；截止：2026-11-12";
  var first=document(project,text);
  for(int i=0;i<10;i++) assertEquals(first.get("id"),document(project,text).get("id"));
  String proposal=proposal(first);
  for(int i=0;i<10;i++) send(post("/api/proposals/"+proposal+"/approve"),approval(null),200);
  assertEquals(1,count("source_document"));assertEquals(1,count("extraction_job"));
  assertEquals(1,count("task"));assertEquals(1,count("task_change"));
 }
 @Test void staleApprovalReturns409AndCanBeReviewedAgain() throws Exception {
  String project=project();String id=createTask(project).get("id").asText();
  var proposal=proposal(document(project,"更新任务："+id+"；负责人：李明；截止：2026-11-15"));
  var edit=approval(0L);edit.put("status","IN_PROGRESS");
  send(patch("/api/tasks/"+id),edit,200);
  var conflict=send(post("/api/proposals/"+proposal+"/approve"),approval(0L),409);
  assertEquals("VERSION_CONFLICT",conflict.get("code").asText());
  assertEquals("PENDING",db.queryForObject("SELECT status FROM change_proposal WHERE id=?",String.class,UUID.fromString(proposal)));
  send(post("/api/proposals/"+proposal+"/approve"),approval(1L),200);
  assertEquals(2,db.queryForObject("SELECT version FROM task WHERE id=?",Integer.class,UUID.fromString(id)));
 }
 @Test void competingTaskWritesOnlyOneWins() throws Exception {
  var task=createTask(project());UUID id=UUID.fromString(task.get("id").asText());
  var input=new Contracts.TaskInput("接口联调","李明",java.time.LocalDate.of(2026,11,15),Contracts.TaskStatus.IN_PROGRESS,0L);
  var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   Callable<Integer> action=()->{gate.await();try {service.editTask(id,input);return 200;} catch(ApiException e){return e.status;}};
   var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
   assertEquals(List.of(200,409),java.util.stream.Stream.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)).sorted().toList());
   assertEquals(2,count("task_change"));
  } finally { pool.shutdownNow(); }
 }
 @Test void concurrentApprovalIsIdempotent() throws Exception {
  UUID proposal=UUID.fromString(proposal(document(project(),"新增任务：接口联调；负责人：李明；截止：2026-11-12")));
  var input=new Contracts.ApprovalInput("接口联调","李明",java.time.LocalDate.of(2026,11,12),null);
  var pool=Executors.newFixedThreadPool(4);
  try {
   var work=new ArrayList<Callable<Object>>();
   for(int i=0;i<8;i++) work.add(()->service.approve(proposal,input));
   for(var future:pool.invokeAll(work)) assertNotNull(future.get(15,TimeUnit.SECONDS));
   assertEquals(1,count("task"));assertEquals(1,count("task_change"));
  } finally { pool.shutdownNow(); }
 }
 @Test void crossProjectTargetRetainsFailureWithoutChangingTasks() throws Exception {
  String first=project();String other=project();String task=createTask(first).get("id").asText();
  var failed=send(post("/api/projects/"+other+"/documents"),Map.of("title","bad","content","更新任务："+task+"；负责人：李明；截止：2026-11-15","referenceDate","2026-11-10"),202);
  assertEquals("FAILED",failed.path("jobs").get(0).path("status").asText());
  assertEquals(2,count("source_document"));assertEquals(2,count("extraction_job"));assertEquals(1,count("change_proposal"));
 }
 @Test void databaseFailureRollsBackTaskApprovalAndHistory() throws Exception {
  UUID proposal=UUID.fromString(proposal(document(project(),"新增任务：接口联调；负责人：李明；截止：2026-11-12")));
  db.execute("CREATE FUNCTION test_history_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected history failure'; END $$");
  db.execute("CREATE TRIGGER test_history_failure BEFORE INSERT ON task_change FOR EACH ROW EXECUTE FUNCTION test_history_failure()");
  try {
   assertThrows(org.springframework.dao.DataAccessException.class,()->service.approve(proposal,new Contracts.ApprovalInput("接口联调","李明",java.time.LocalDate.of(2026,11,12),null)));
   assertEquals(0,count("task"));assertEquals(0,count("task_change"));
   assertEquals("PENDING",db.queryForObject("SELECT status FROM change_proposal WHERE id=?",String.class,proposal));
  } finally {
   db.execute("DROP TRIGGER test_history_failure ON task_change");db.execute("DROP FUNCTION test_history_failure()");
  }
 }
 @Test void rejectionIsTerminalAndRepeatable() throws Exception {
  var id=proposal(document(project(),"新增任务：接口联调；负责人：李明；截止：2026-11-12"));
  send(post("/api/proposals/"+id+"/reject"),null,200);
  send(post("/api/proposals/"+id+"/reject"),null,200);
  send(post("/api/proposals/"+id+"/approve"),approval(null),409);assertEquals(0,count("task"));
 }
 @Test void malformedRequestsAndZeroCandidatesAreHandled() throws Exception {
  send(post("/api/projects"),Map.of("name","x","timezone","not-a-zone"),400);
  String id=project();
  send(post("/api/projects/"+id+"/documents"),Map.of("title","bad","content"," ","referenceDate","2026-11-10"),400);
  var invalid=send(post("/api/projects/"+id+"/documents"),Map.of("title","bad","content","新增任务：接口联调；负责人：李明；截止：2026-99-10","referenceDate","2026-11-10"),202);
  assertEquals("INVALID_MOCK_INPUT",invalid.path("jobs").get(0).path("error_code").asText());
  assertEquals(1,count("source_document"));assertEquals(0,count("change_proposal"));
  var empty=document(id,"今天没有新的任务。");
  assertEquals(0,empty.get("proposals").size());assertEquals("SUCCEEDED",empty.get("jobs").get(0).get("status").asText());
 }
 @Test void missingVersionAndUnknownFieldsAreRejected() throws Exception {
  String project=project();String id=createTask(project).get("id").asText();
  String proposal=proposal(document(project,"更新任务："+id+"；负责人：李明；截止：2026-11-15"));
  send(post("/api/proposals/"+proposal+"/approve"),approval(null),400);
  var bad=approval(0L);bad.put("status","DONE");
  send(post("/api/proposals/"+proposal+"/approve"),bad,400);
  send(get("/api/projects/"+project+"/tasks?page=-1"),null,400);
  send(get("/api/tasks/not-a-uuid"),null,400);
 }

 @Test void documentListAndWorkspaceCountsReflectReviews() throws Exception {
  String project=project();
  var input=Map.of("title","P2纪要","content","新增任务：接口联调；负责人：李明；截止：2026-11-12\n新增任务：接口文档；负责人：王芳；截止：2026-11-13","referenceDate","2026-11-11");
  var doc=send(post("/api/projects/"+project+"/documents"),input,202);
  assertFalse(doc.get("duplicate").asBoolean());
  var duplicate=send(post("/api/projects/"+project+"/documents"),input,202);
  assertTrue(duplicate.get("duplicate").asBoolean());
  var list=send(get("/api/projects/"+project+"/documents?size=1"),null,200);
  assertEquals(1,list.size()); assertEquals(2,list.get(0).get("proposal_count").asInt());
  assertEquals(2,list.get(0).get("pending_count").asInt());
  send(post("/api/proposals/"+proposal(doc)+"/approve"),approval(null),200);
  var projects=send(get("/api/projects"),null,200);
  assertEquals(1,projects.get(0).get("document_count").asInt());
  assertEquals(1,projects.get(0).get("task_count").asInt());
  assertEquals(1,projects.get(0).get("pending_count").asInt());
  send(post("/api/proposals/"+doc.get("proposals").get(1).get("id").asText()+"/reject"),null,200);
  list=send(get("/api/projects/"+project+"/documents"),null,200);
  assertEquals(0,list.get(0).get("pending_count").asInt());
  send(get("/api/projects/"+project+"/documents?page=-1"),null,400);
  send(get("/api/projects/"+UUID.randomUUID()+"/documents"),null,404);
 }
 @Test void documentListAndCountsStayInsideProject() throws Exception {
  String first=project(),second=project();
  createTask(first);
  document(second,"无任务的普通记录");
  var list=send(get("/api/projects/"+second+"/documents"),null,200);
  assertEquals(1,list.size());assertEquals(second,list.get(0).get("project_id").asText());
  assertEquals(0,list.get(0).get("proposal_count").asInt());
  for(var item:send(get("/api/projects"),null,200)) {
   assertEquals(1,item.get("document_count").asInt());
   assertEquals(item.get("id").asText().equals(first)?1:0,item.get("task_count").asInt());
  }
 }
}
