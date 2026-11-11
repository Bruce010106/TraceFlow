package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"traceflow.ai.mode=mock","traceflow.queue.enabled=false"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){TestDatabase.register(registry);}
    static final String PASSWORD="TraceFlow-test-password-2026";
    static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Autowired ExtractionQueue queue;@Autowired PlatformTransactionManager manager;
    MockHttpSession owner,editor,viewer,outsider;String project;
    @BeforeEach void setup()throws Exception{
        db.execute("TRUNCATE task_change,change_proposal,extraction_job,source_document,task,project,app_user CASCADE");
        for(String name:List.of("owner","editor","viewer","outsider"))db.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)",UUID.randomUUID(),name,HASH);
        owner=login("owner");editor=login("editor");viewer=login("viewer");outsider=login("outsider");
        project=send(owner,post("/api/projects"),Map.of("name","Private","timezone","Asia/Hong_Kong"),200).path("id").asText();
        member("editor","EDITOR");member("viewer","VIEWER");
    }
    String csrf(MockHttpSession session)throws Exception{return json.readTree(mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("token").asText();}
    MockHttpSession login(String username)throws Exception{
        var session=new MockHttpSession();String before=session.getId();
        send(session,post("/api/auth/login"),Map.of("username",username,"password",PASSWORD),200);
        assertNotEquals(before,session.getId());return session;
    }
    JsonNode send(MockHttpSession session,MockHttpServletRequestBuilder request,Object body,int expected)throws Exception{
        request.session(session);if(!"GET".equals(request.buildRequest(webContext()).getMethod()))request.header("X-CSRF-TOKEN",csrf(session));
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    org.springframework.mock.web.MockServletContext webContext(){return new org.springframework.mock.web.MockServletContext();}
    void member(String name,String role)throws Exception{send(owner,put("/api/projects/"+project+"/members/"+name),Map.of("role",role),200);}
    Map<String,Object> documentInput(String text){return Map.of("title","权限验证","content",text,"referenceDate","2026-11-11");}
    JsonNode document(MockHttpSession session,String text)throws Exception{return send(session,post("/api/projects/"+project+"/documents"),documentInput(text),202);}
    JsonNode extract(JsonNode doc)throws Exception{queue.execute(queue.claim("auth-test-worker").orElseThrow());return send(owner,get("/api/documents/"+doc.path("id").asText()),null,200);}
    Map<String,Object> approval(){return Map.of("title","接口联调","assigneeName","李明","dueDate","2026-11-13");}
    String proposal(JsonNode doc){return doc.path("proposals").get(0).path("id").asText();}

    @Test void anonymousCannotReadOrWriteAnyBusinessRoute()throws Exception{
        var anonymous=new MockHttpSession();String id=UUID.randomUUID().toString();
        for(String path:List.of("/api/auth/me","/api/projects","/api/projects/"+project+"/documents","/api/projects/"+project+"/tasks","/api/projects/"+project+"/members","/api/documents/"+id,"/api/tasks/"+id,"/api/tasks/"+id+"/history"))
            send(anonymous,get(path),null,401);
        send(anonymous,post("/api/projects"),Map.of("name","hidden","timezone","UTC"),401);
        send(anonymous,post("/api/documents/"+id+"/extract"),null,401);
        send(anonymous,post("/api/proposals/"+id+"/approve"),approval(),401);
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
    @Test void registrationNormalizesAndHashesPasswordsWithoutClaimingLegacyProjects()throws Exception{
        UUID legacy=UUID.randomUUID();db.update("INSERT INTO project(id,name,timezone) VALUES(?,'legacy','UTC')",legacy);
        var session=new MockHttpSession();var created=send(session,post("/api/auth/register"),Map.of("username","New_User","password",PASSWORD),200);
        assertEquals("new_user",created.path("username").asText());assertFalse(created.has("password_hash"));
        String hash=db.queryForObject("SELECT password_hash FROM app_user WHERE username='new_user'",String.class);
        assertTrue(new BCryptPasswordEncoder().matches(PASSWORD,hash));assertNotEquals(PASSWORD,hash);
        send(session,post("/api/auth/register"),Map.of("username","NEW_USER","password",PASSWORD),409);
        send(session,post("/api/auth/register"),Map.of("username","bad user","password",PASSWORD),400);
        send(session,post("/api/auth/register"),Map.of("username","other","password","short"),400);
        send(session,post("/api/auth/register"),Map.of("username","other","password","密".repeat(30)),400);
        var logged=login("NEW_USER");assertEquals(0,send(logged,get("/api/projects"),null,200).size());
        send(logged,get("/api/projects/"+legacy+"/tasks"),null,404);
        send(session,post("/api/auth/login"),Map.of("username","new_user","password","incorrect-password"),401);
    }
    @Test void csrfRotationLogoutAndInvalidCredentialsAreEnforced()throws Exception{
        var anonymous=new MockHttpSession();String old=csrf(anonymous);
        mvc.perform(post("/api/auth/login").session(anonymous).contentType("application/json").content(json.writeValueAsString(Map.of("username","owner","password",PASSWORD))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        send(anonymous,post("/api/auth/login"),Map.of("username","owner","password",PASSWORD),200);
        mvc.perform(post("/api/projects").session(anonymous).header("X-CSRF-TOKEN",old).contentType("application/json").content("{\"name\":\"bad\",\"timezone\":\"UTC\"}"))
            .andExpect(status().isForbidden());
        send(anonymous,get("/api/auth/me"),null,200);
        send(anonymous,post("/api/auth/logout"),null,200);assertTrue(anonymous.isInvalid());
        send(new MockHttpSession(),get("/api/projects"),null,401);
        send(new MockHttpSession(),post("/api/auth/login"),Map.of("username","missing","password",PASSWORD),401);
    }
    @Test void editorWorkflowViewerReadAndOutsiderConcealmentCoverEveryResource()throws Exception{
        String text="新增任务：接口联调；负责人：李明；截止：2026-11-13";
        var doc=extract(document(editor,text));String docId=doc.path("id").asText(),proposal=proposal(doc);
        var task=send(editor,post("/api/proposals/"+proposal+"/approve"),approval(),200);String taskId=task.path("id").asText();
        assertEquals(task,send(editor,post("/api/proposals/"+proposal+"/approve"),approval(),200));
        for(String path:List.of("/api/projects/"+project+"/documents","/api/projects/"+project+"/tasks","/api/projects/"+project+"/members","/api/documents/"+docId,"/api/tasks/"+taskId,"/api/tasks/"+taskId+"/history")){
            send(viewer,get(path),null,200);send(outsider,get(path),null,404);
        }
        assertEquals(0,send(outsider,get("/api/projects"),null,200).size());
        var edit=new HashMap<String,Object>(approval());edit.put("status","IN_PROGRESS");edit.put("expectedTaskVersion",0);
        for(var session:List.of(viewer,outsider)){
            int denied=session==viewer?403:404;
            send(session,post("/api/projects/"+project+"/documents"),documentInput(text),denied);
            send(session,post("/api/documents/"+docId+"/extract"),null,denied);
            send(session,post("/api/proposals/"+proposal+"/approve"),approval(),denied);
            send(session,post("/api/proposals/"+proposal+"/reject"),null,denied);
            send(session,patch("/api/tasks/"+taskId),edit,denied);
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM task_change",Integer.class));
        var history=send(viewer,get("/api/tasks/"+taskId+"/history"),null,200);
        assertEquals("editor",history.get(0).path("actor_username").asText());
        assertEquals(db.queryForObject("SELECT id FROM app_user WHERE username='editor'",UUID.class),db.queryForObject("SELECT created_by FROM source_document WHERE id=?",UUID.class,UUID.fromString(docId)));
        send(editor,patch("/api/tasks/"+taskId),edit,200);
    }
    @Test void onlyOwnerManagesMembershipAndCannotRemoveOrDemoteSelf()throws Exception{
        for(var session:List.of(editor,viewer,outsider)){
            int denied=session==outsider?404:403;
            send(session,put("/api/projects/"+project+"/members/outsider"),Map.of("role","EDITOR"),denied);
            send(session,delete("/api/projects/"+project+"/members/editor"),null,denied);
        }
        send(owner,delete("/api/projects/"+project+"/members/owner"),null,409);
        send(owner,put("/api/projects/"+project+"/members/owner"),Map.of("role","VIEWER"),409);
        send(owner,put("/api/projects/"+project+"/members/viewer"),Map.of("role","OWNER"),400);
        send(owner,put("/api/projects/"+project+"/members/viewer"),Map.of(),400);
        send(owner,put("/api/projects/"+project+"/members/missing"),Map.of("role","VIEWER"),404);
        assertFalse(send(viewer,get("/api/projects/"+project+"/members"),null,200).toString().contains("password"));
    }
    @Test void downgradeAndRemovalImmediatelyAffectExistingSessionsAndIdempotentReplays()throws Exception{
        var doc=extract(document(editor,"新增任务：接口联调；负责人：李明；截止：2026-11-13"));String proposal=proposal(doc);
        send(editor,post("/api/proposals/"+proposal+"/approve"),approval(),200);
        member("editor","VIEWER");
        send(editor,post("/api/proposals/"+proposal+"/approve"),approval(),403);
        assertEquals("VIEWER",send(editor,get("/api/projects"),null,200).get(0).path("role").asText());
        send(owner,delete("/api/projects/"+project+"/members/editor"),null,200);
        send(editor,get("/api/documents/"+doc.path("id").asText()),null,404);
        send(editor,post("/api/proposals/"+proposal+"/approve"),approval(),404);
        assertEquals(0,send(editor,get("/api/projects"),null,200).size());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM task_change",Integer.class));
    }
    @Test void queuedExtractionContinuesAfterUserLogoutAndStillRequiresReview()throws Exception{
        var doc=document(editor,"新增任务：接口联调；负责人：李明；截止：2026-11-13");
        send(editor,post("/api/auth/logout"),null,200);
        queue.execute(queue.claim("system-without-user-session").orElseThrow());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM task",Integer.class));
        var ready=send(owner,get("/api/documents/"+doc.path("id").asText()),null,200);
        assertEquals("SUCCEEDED",ready.path("jobs").get(0).path("status").asText());
        send(owner,post("/api/proposals/"+proposal(ready)+"/approve"),approval(),200);
    }
    @Test void explicitLegacyClaimIsIdempotentAndPreservesExistingOwners()throws Exception{
        UUID legacy=UUID.randomUUID();db.update("INSERT INTO project(id,name,timezone) VALUES(?,'legacy','UTC')",legacy);
        assertThrows(IllegalStateException.class,()->new LegacyProjectClaim(db,manager,"not-registered").run(null));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM project_member WHERE project_id=?",Integer.class,legacy));
        var claim=new LegacyProjectClaim(db,manager,"OWNER");claim.run(null);claim.run(null);
        assertEquals(2,send(owner,get("/api/projects"),null,200).size());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM project_member WHERE project_id=?",Integer.class,legacy));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM project_member WHERE project_id=?",Integer.class,UUID.fromString(project)));
        send(outsider,get("/api/projects/"+legacy+"/tasks"),null,404);
    }
    @Test void authenticatedVersionConflictPreservesPendingProposalAndOriginalReplaySnapshot()throws Exception{
        var doc=extract(document(editor,"新增任务：接口联调；负责人：李明；截止：2026-11-13"));
        String createProposal=proposal(doc);var initial=send(editor,post("/api/proposals/"+createProposal+"/approve"),approval(),200);
        String task=initial.path("id").asText();
        var update=extract(document(editor,"更新任务："+task+"；负责人：李明；截止：2026-11-15"));
        var edit=new HashMap<String,Object>(approval());edit.put("status","IN_PROGRESS");edit.put("expectedTaskVersion",0);
        send(owner,patch("/api/tasks/"+task),edit,200);
        var reviewed=new HashMap<String,Object>(approval());reviewed.put("expectedTaskVersion",0);
        var conflict=send(editor,post("/api/proposals/"+proposal(update)+"/approve"),reviewed,409);
        assertEquals("VERSION_CONFLICT",conflict.path("code").asText());
        assertEquals("PENDING",send(viewer,get("/api/documents/"+update.path("id").asText()),null,200).path("proposals").get(0).path("status").asText());
        reviewed.put("expectedTaskVersion",1);send(editor,post("/api/proposals/"+proposal(update)+"/approve"),reviewed,200);
        assertEquals(initial,send(editor,post("/api/proposals/"+createProposal+"/approve"),approval(),200));
        assertEquals(3,send(viewer,get("/api/tasks/"+task+"/history"),null,200).size());
    }
    @Test void writeWaitingOnProjectLockRechecksRevokedPermission()throws Exception{
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var revocation=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(ignored->{
                db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE",UUID.fromString(project));
                db.update("DELETE FROM project_member WHERE project_id=? AND user_id IN (SELECT id FROM app_user WHERE username='editor')",UUID.fromString(project));
                acquired.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(acquired.await(5,TimeUnit.SECONDS));
            var writing=pool.submit(()->send(editor,post("/api/projects/"+project+"/documents"),documentInput("新增任务：被撤权写入；负责人：李明；截止：2026-11-13"),404));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT * FROM project WHERE id=%'",Integer.class)==0&&System.nanoTime()<deadline)Thread.sleep(25);
            assertFalse(writing.isDone());
            release.countDown();revocation.get(5,TimeUnit.SECONDS);
            assertEquals("NOT_FOUND",writing.get(5,TimeUnit.SECONDS).path("code").asText());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM source_document",Integer.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
