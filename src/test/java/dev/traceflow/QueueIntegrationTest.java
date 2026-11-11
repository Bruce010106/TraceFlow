package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"traceflow.ai.mode=mock","traceflow.queue.enabled=false","traceflow.queue.retry-base-ms=200","traceflow.queue.retry-max-ms=2000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class QueueIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){TestDatabase.register(registry);}
    @Autowired TraceFlowService service;@Autowired ExtractionQueue queue;@Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;@Autowired TaskExtractor extractor;@Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @BeforeEach void clean(){db.execute("TRUNCATE task_change,change_proposal,extraction_job,source_document,task,project CASCADE");}
    UUID project(){return (UUID)service.createProject(new Contracts.ProjectInput("P4","Asia/Hong_Kong")).get("id");}
    UUID enqueue(UUID project,String text){return (UUID)service.importDocument(project,new Contracts.DocumentInput("纪要",text,LocalDate.of(2026,11,11))).get("id");}
    UUID enqueue(){return enqueue(project(),"新增任务：接口联调；负责人：李明；截止：2026-11-13");}
    Map<String,Object> job(UUID doc){return db.queryForMap("SELECT * FROM extraction_job WHERE document_id=? ORDER BY generation DESC LIMIT 1",doc);}
    int count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    void expire(ExtractionQueue.Lease lease){db.update("UPDATE extraction_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",lease.jobId());}
    void due(UUID doc){db.update("UPDATE extraction_job SET next_run_at=clock_timestamp()-interval '1 second' WHERE document_id=? AND status='RETRY_WAIT'",doc);}
    TaskExtractor.Result result(ExtractionQueue.Lease lease){return extractor.extract(lease.input());}

    @Test void httpAcknowledgesDurableQueueBeforeAnyExtraction()throws Exception{
        UUID project=project();
        var response=mvc.perform(post("/api/projects/"+project+"/documents").contentType("application/json")
            .content(json.writeValueAsString(Map.of("title","异步","content","新增任务：接口联调；负责人：李明；截止：2026-11-13","referenceDate","2026-11-11"))))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var body=json.readTree(response);
        assertEquals("QUEUED",body.path("jobs").get(0).path("status").asText());
        assertEquals(0,body.path("jobs").get(0).path("attempt_count").asInt());
        assertEquals(0,count("extraction_attempt"));assertEquals(0,count("change_proposal"));assertEquals(0,count("task"));
        assertFalse(body.path("jobs").get(0).has("lease_token"));
    }
    @Test void concurrentEnqueueHasOneDocumentAndOneActiveJob()throws Exception{
        UUID project=project();var pool=Executors.newFixedThreadPool(4);
        try{
            var work=new ArrayList<Callable<UUID>>();
            for(int i=0;i<8;i++)work.add(()->enqueue(project,"新增任务：接口联调；负责人：李明；截止：2026-11-13"));
            var ids=new HashSet<UUID>();for(var future:pool.invokeAll(work))ids.add(future.get(10,TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(1,count("source_document"));assertEquals(1,count("extraction_job"));
            assertEquals(0,count("extraction_attempt"));
        }finally{pool.shutdownNow();}
    }
    @Test void skipLockedAllowsAnotherConsumerToTakeAnotherJob()throws Exception{
        UUID first=enqueue(),second=enqueue();
        UUID locked=(UUID)job(first).get("id");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var blocker=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(ignored->{
                db.queryForList("SELECT id FROM extraction_job WHERE id=? FOR UPDATE",locked);entered.countDown();
                try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var claim=pool.submit(()->queue.claim("consumer-2").orElseThrow()).get(3,TimeUnit.SECONDS);
            assertEquals(second,claim.documentId());
            release.countDown();blocker.get(5,TimeUnit.SECONDS);
            var other=queue.claim("consumer-1").orElseThrow();assertEquals(first,other.documentId());
            assertNotEquals(other.token(),claim.token());assertTrue(queue.claim("third").isEmpty());
            queue.execute(claim);queue.execute(other);assertEquals(2,count("change_proposal"));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void concurrentClaimersNeverOwnTheSameAttempt()throws Exception{
        enqueue();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try{
            var futures=new ArrayList<Future<Optional<ExtractionQueue.Lease>>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return queue.claim("race-"+Thread.currentThread().getId());}));
            start.countDown();int claimed=0;
            for(var future:futures)if(future.get(5,TimeUnit.SECONDS).isPresent())claimed++;
            assertEquals(1,claimed);assertEquals(1,count("extraction_attempt"));
        }finally{pool.shutdownNow();}
    }
    @Test void expiredAndSupersededTokensCannotRenewFailOrPublish() {
        UUID doc=enqueue();var old=queue.claim("old").orElseThrow();var output=result(old);
        expire(old);assertFalse(queue.renew(old));assertFalse(queue.complete(old,output));assertFalse(queue.fail(old,"MODEL_TIMEOUT",1));
        assertEquals(0,count("change_proposal"));assertEquals(1,queue.recoverExpired(20));
        assertEquals("RETRY_WAIT",job(doc).get("status"));due(doc);
        var current=queue.claim("new").orElseThrow();assertEquals(2,current.attempt());assertNotEquals(old.token(),current.token());
        assertTrue(queue.complete(current,result(current)));
        assertFalse(queue.complete(old,output));assertFalse(queue.fail(old,"MODEL_TIMEOUT",1));assertFalse(queue.renew(old));
        assertEquals(1,count("change_proposal"));assertEquals("SUCCEEDED",job(doc).get("status"));
        assertEquals(List.of("EXPIRED","SUCCEEDED"),db.queryForList("SELECT status FROM extraction_attempt ORDER BY attempt_no",String.class));
    }
    @Test void heartbeatExtendsOnlyAnUnexpiredLease(){
        UUID doc=enqueue();var lease=queue.claim("heartbeat").orElseThrow();
        db.update("UPDATE extraction_job SET lease_until=clock_timestamp()+interval '1 second' WHERE id=?",lease.jobId());
        assertTrue(queue.renew(lease));
        assertTrue(db.queryForObject("SELECT lease_until>clock_timestamp()+interval '20 seconds' FROM extraction_job WHERE id=?",Boolean.class,lease.jobId()));
        expire(lease);assertFalse(queue.renew(lease));assertEquals("RUNNING",job(doc).get("status"));
    }
    @Test void transientRetriesAreDelayedAndBoundedThenManualRetryStartsANewGeneration(){
        UUID doc=enqueue();
        for(int attempt=1;attempt<=3;attempt++){
            var lease=queue.claim("retry").orElseThrow();assertEquals(attempt,lease.attempt());
            assertTrue(queue.fail(lease,"MODEL_RATE_LIMITED",1));
            if(attempt<3){
                assertEquals("RETRY_WAIT",job(doc).get("status"));
                assertTrue(db.queryForObject("SELECT next_run_at>clock_timestamp() FROM extraction_job WHERE id=?",Boolean.class,lease.jobId()));
                assertTrue(queue.claim("too-early").isEmpty());due(doc);
            }
        }
        assertEquals("FAILED",job(doc).get("status"));assertTrue(queue.claim("exhausted").isEmpty());
        service.retryExtraction(doc);service.retryExtraction(doc);
        assertEquals(2,count("extraction_job"));assertEquals(2,((Number)job(doc).get("generation")).intValue());
        assertEquals(0,((Number)job(doc).get("attempt_count")).intValue());assertEquals(3,count("extraction_attempt"));
        var lease=queue.claim("manual").orElseThrow();queue.execute(lease);assertEquals("SUCCEEDED",job(doc).get("status"));
        assertEquals(1,count("change_proposal"));
    }
    @Test void permanentFailuresDoNotConsumeTheRetryBudget(){
        for(String code:List.of("MODEL_AUTH_ERROR","MODEL_INVALID_OUTPUT","MODEL_INVALID_EVIDENCE","MODEL_NOT_CONFIGURED","MODEL_CONFIG_CHANGED")){
            UUID doc=enqueue();var lease=queue.claim("permanent").orElseThrow();assertTrue(queue.fail(lease,code,1));
            assertEquals("FAILED",job(doc).get("status"));assertNull(job(doc).get("next_run_at"));
            assertEquals(1,((Number)job(doc).get("attempt_count")).intValue());assertTrue(queue.claim("unexpected").isEmpty());
        }
    }
    @Test void gracefulAbandonIsDurableAndOldResultsAreFenced(){
        UUID doc=enqueue();var old=queue.claim("shutdown").orElseThrow();assertTrue(queue.abandon(old));
        assertEquals("RETRY_WAIT",job(doc).get("status"));assertFalse(queue.complete(old,result(old)));
        assertEquals("ABANDONED",db.queryForObject("SELECT status FROM extraction_attempt WHERE job_id=?",String.class,old.jobId()));
        var next=queue.claim("restart").orElseThrow();queue.execute(next);assertEquals("SUCCEEDED",job(doc).get("status"));
    }
    @Test void expiredFinalAttemptMovesToTerminalFailure(){
        UUID doc=enqueue();db.update("UPDATE extraction_job SET max_attempts=1 WHERE document_id=?",doc);
        var lease=queue.claim("last").orElseThrow();expire(lease);queue.recoverExpired(20);
        assertEquals("FAILED",job(doc).get("status"));assertEquals("LEASE_EXPIRED",job(doc).get("error_code"));
        assertTrue(queue.claim("no-more").isEmpty());
    }
    @Test void delayedWorkerUsesImportSnapshotAndHumanVersionConflictRemains(){
        UUID project=project(),task=UUID.randomUUID();
        db.update("INSERT INTO task(id,project_id,title,assignee_name,due_date) VALUES(?,?,?,?,?)",task,project,"接口联调","李明",LocalDate.of(2026,11,13));
        UUID doc=enqueue(project,"更新任务："+task+"；负责人：李明；截止：2026-11-15");
        service.editTask(task,new Contracts.TaskInput("接口联调","李明",LocalDate.of(2026,11,16),Contracts.TaskStatus.IN_PROGRESS,0L));
        var lease=queue.claim("delayed").orElseThrow();queue.execute(lease);
        var proposal=db.queryForMap("SELECT * FROM change_proposal WHERE job_id=?",lease.jobId());
        assertEquals(0L,((Number)proposal.get("base_task_version")).longValue());
        var stale=new Contracts.ApprovalInput("接口联调","李明",LocalDate.of(2026,11,15),0L);
        assertEquals(409,assertThrows(ApiException.class,()->service.approve((UUID)proposal.get("id"),stale)).status);
        var fresh=new Contracts.ApprovalInput("接口联调","李明",LocalDate.of(2026,11,15),1L);
        service.approve((UUID)proposal.get("id"),fresh);service.approve((UUID)proposal.get("id"),fresh);
        assertEquals(2,service.task(task).get("version") instanceof Number n?n.intValue():-1);assertEquals(2,count("task_change"));
    }
    @Test void persistenceFailureNeverLeavesHalfABatchAndCanRetry(){
        UUID doc=enqueue(project(),"新增任务：接口联调；负责人：李明；截止：2026-11-13\n新增任务：整理文档；负责人：王芳；截止：2026-11-14");
        db.execute("CREATE FUNCTION p4_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ordinal=1 THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER p4_failure BEFORE INSERT ON change_proposal FOR EACH ROW EXECUTE FUNCTION p4_failure()");
        try{queue.execute(queue.claim("fail").orElseThrow());assertEquals(0,count("change_proposal"));assertEquals("RETRY_WAIT",job(doc).get("status"));}
        finally{db.execute("DROP TRIGGER p4_failure ON change_proposal");db.execute("DROP FUNCTION p4_failure()");}
        due(doc);queue.execute(queue.claim("recovered").orElseThrow());assertEquals(2,count("change_proposal"));assertEquals(0,count("task"));
    }
    @Test void leaseIsRecheckedAfterProposalInsertsBeforeCommit(){
        UUID doc=enqueue();var lease=queue.claim("slow-commit").orElseThrow();
        db.execute("CREATE FUNCTION p4_slow_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(4); RETURN NEW; END $$");
        db.execute("CREATE TRIGGER p4_slow_insert BEFORE INSERT ON change_proposal FOR EACH ROW EXECUTE FUNCTION p4_slow_insert()");
        try{
            db.update("UPDATE extraction_job SET lease_until=clock_timestamp()+interval '3 seconds' WHERE id=?",lease.jobId());
            long start=System.nanoTime();assertFalse(queue.complete(lease,result(lease)));
            assertTrue((System.nanoTime()-start)/1_000_000>=3900);assertEquals(0,count("change_proposal"));
            assertEquals("RUNNING",job(doc).get("status"));assertEquals(1,queue.recoverExpired(20));
        }finally{db.execute("DROP TRIGGER p4_slow_insert ON change_proposal");db.execute("DROP FUNCTION p4_slow_insert()");}
    }

    @Test void heartbeatSkipsABusyRowAndDoesNotWaitIntoExpiry()throws Exception{
        UUID doc=enqueue();var lease=queue.claim("busy-heartbeat").orElseThrow();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(ignored->{
                db.queryForList("SELECT id FROM extraction_job WHERE id=? FOR UPDATE",lease.jobId());entered.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertFalse(pool.submit(()->queue.renew(lease)).get(2,TimeUnit.SECONDS));
            release.countDown();holder.get(3,TimeUnit.SECONDS);
            assertTrue(queue.renew(lease));expire(lease);assertFalse(queue.renew(lease));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
