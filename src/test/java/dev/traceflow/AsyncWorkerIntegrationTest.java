package dev.traceflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"traceflow.ai.mode=mock","traceflow.queue.enabled=true","traceflow.queue.concurrency=2",
    "traceflow.queue.poll-ms=200","traceflow.queue.lease-seconds=5","traceflow.queue.heartbeat-ms=500",
    "traceflow.queue.retry-base-ms=200","traceflow.queue.retry-max-ms=1000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AsyncWorkerIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){TestDatabase.register(registry);}
    @TestConfiguration static class Config {
        @Bean @Primary ControlledExtractor controlledExtractor(ObjectMapper json){return new ControlledExtractor(json);}
    }
    static final class ControlledExtractor implements TaskExtractor {
        final MockExtractor delegate;final AtomicInteger calls=new AtomicInteger();
        volatile boolean failFirst;volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
        ControlledExtractor(ObjectMapper json){delegate=new MockExtractor(json);}
        public String mode(){return "mock";}public String model(){return "worker-test";}
        public String promptVersion(){return "mock-v1";}public boolean configured(){return true;}
        public Result extract(Input input){
            int call=calls.incrementAndGet();
            if(failFirst&&call==1)throw new ExtractionFailure("MODEL_RATE_LIMITED");
            entered.countDown();
            try{if(!release.await(12,TimeUnit.SECONDS))throw new ExtractionFailure("MODEL_TIMEOUT");}
            catch(InterruptedException e){throw new ExtractionFailure("MODEL_INTERRUPTED");}
            return delegate.extract(input);
        }
        void reset(){calls.set(0);failFirst=false;entered=new CountDownLatch(1);release=new CountDownLatch(0);}
    }
    @Autowired TraceFlowService service;@Autowired ExtractionQueue queue;@Autowired ExtractionWorker worker;
    @Autowired ControlledExtractor model;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    @BeforeEach void clean(){
        worker.close();model.release.countDown();
        db.execute("TRUNCATE task_change,change_proposal,extraction_job,source_document,task,project CASCADE");
        model.reset();worker.start();
    }
    @AfterEach void stop(){model.release.countDown();worker.close();}
    UUID project(){return (UUID)service.createProject(new Contracts.ProjectInput("P4 worker","Asia/Hong_Kong")).get("id");}
    UUID enqueue(UUID project){return (UUID)service.importDocument(project,new Contracts.DocumentInput("后台记录",
        "新增任务：接口联调；负责人：李明；截止：2026-11-13",LocalDate.of(2026,11,11))).get("id");}
    Map<String,Object> job(UUID doc){return db.queryForMap("SELECT * FROM extraction_job WHERE document_id=? ORDER BY generation DESC LIMIT 1",doc);}
    static void await(BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Background state did not arrive before deadline");
    }
    @Test void httpReturnsWhileWorkerRetriesAndHeartbeatsKeepSlowCallAlive()throws Exception{
        model.failFirst=true;model.release=new CountDownLatch(1);
        UUID project=project();
        var response=mvc.perform(post("/api/projects/"+project+"/documents").contentType("application/json")
            .content(json.writeValueAsString(Map.of("title","后台记录","content","新增任务：接口联调；负责人：李明；截止：2026-11-13","referenceDate","2026-11-11"))))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var body=json.readTree(response);UUID doc=UUID.fromString(body.path("id").asText());
        assertEquals("QUEUED",body.path("jobs").get(0).path("status").asText());
        assertTrue(model.entered.await(8,TimeUnit.SECONDS));assertEquals(2,model.calls.get());
        Thread.sleep(5500); // Longer than the five-second lease; requires the independent heartbeat thread.
        assertEquals("RUNNING",job(doc).get("status"));assertEquals(2,((Number)job(doc).get("attempt_count")).intValue());
        assertTrue(db.queryForObject("SELECT lease_until>clock_timestamp() FROM extraction_job WHERE document_id=?",Boolean.class,doc));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM change_proposal",Integer.class));
        model.release.countDown();await(()->"SUCCEEDED".equals(job(doc).get("status")));
        assertEquals(List.of("FAILED","SUCCEEDED"),db.queryForList("SELECT status FROM extraction_attempt ORDER BY attempt_no",String.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM change_proposal",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM task",Integer.class));
    }
    @Test void gracefulWorkerRestartResumesTheDurableJob()throws Exception{
        model.release=new CountDownLatch(1);UUID doc=enqueue(project());
        assertTrue(model.entered.await(8,TimeUnit.SECONDS));worker.close();
        assertEquals("RETRY_WAIT",job(doc).get("status"));
        assertEquals("ABANDONED",db.queryForObject("SELECT status FROM extraction_attempt WHERE attempt_no=1",String.class));
        model.release.countDown();worker.start();await(()->"SUCCEEDED".equals(job(doc).get("status")));
        assertEquals(2,((Number)job(doc).get("attempt_count")).intValue());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM change_proposal",Integer.class));
    }
    @Test void pollerRecoversADeadOwnersExpiredLease()throws Exception{
        worker.close();UUID doc=enqueue(project());var lease=queue.claim("dead-owner").orElseThrow();
        db.update("UPDATE extraction_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",lease.jobId());
        worker.start();await(()->"SUCCEEDED".equals(job(doc).get("status")));
        assertEquals(List.of("EXPIRED","SUCCEEDED"),db.queryForList("SELECT status FROM extraction_attempt ORDER BY attempt_no",String.class));
        assertEquals(1,model.calls.get());assertEquals(0,db.queryForObject("SELECT count(*) FROM task",Integer.class));
    }
}
