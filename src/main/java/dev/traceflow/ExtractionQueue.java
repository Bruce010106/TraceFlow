package dev.traceflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL is the durable queue. A lease token fences every state-changing worker operation. */
@Service
public class ExtractionQueue {
    public record Lease(UUID jobId,UUID projectId,UUID documentId,UUID token,int attempt,int maxAttempts,
                        String provider,String model,String promptVersion,TaskExtractor.Input input) {}
    private static final Set<String> TRANSIENT=Set.of("MODEL_TIMEOUT","MODEL_CONNECTION_ERROR","MODEL_UNAVAILABLE",
        "MODEL_RATE_LIMITED","MODEL_INTERRUPTED","QUEUE_STORAGE_ERROR");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TaskExtractor extractor;
    private final ProposalValidator validator;
    private final QueueProperties config;
    private final TransactionTemplate tx;
    public ExtractionQueue(JdbcTemplate db,ObjectMapper json,TaskExtractor extractor,ProposalValidator validator,
                           QueueProperties config,PlatformTransactionManager manager) {
        this.db=db;this.json=json;this.extractor=extractor;this.validator=validator;this.config=config;tx=new TransactionTemplate(manager);
    }
    private String encode(Object value){
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode queue data",e);}
    }

    /** Called inside the document import transaction, while the project is locked. */
    public UUID enqueue(UUID projectId,UUID documentId,LocalDate referenceDate,String timezone,int generation) {
        var tasks=db.queryForList("SELECT id,title,assignee_name,due_date,status,version FROM task WHERE project_id=? AND archived_at IS NULL ORDER BY created_at,id",projectId);
        if(tasks.size()>50)throw new ApiException(400,"TASK_LIMIT","项目未归档任务超过50条");
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO extraction_job(id,document_id,status,attempt_count,generation,max_attempts,next_run_at,
              model,prompt_version,input_snapshot_json,provider)
            VALUES(?,?,'QUEUED',0,?,?,clock_timestamp(),?,?,?::jsonb,?)
            """,id,documentId,generation,config.getMaxAttempts(),extractor.model(),extractor.promptVersion(),
            encode(Map.of("documentId",documentId,"referenceDate",referenceDate,"timezone",timezone,"tasks",tasks)),extractor.provider());
        return id;
    }

    public Optional<Lease> claim(String workerId) {
        return Objects.requireNonNull(tx.execute(ignored->{
            var ready=db.queryForList("""
                SELECT j.*,d.project_id,d.content,d.reference_date,d.timezone
                FROM extraction_job j JOIN source_document d ON d.id=j.document_id
                WHERE j.status IN ('QUEUED','RETRY_WAIT') AND j.next_run_at<=clock_timestamp()
                  AND j.attempt_count<j.max_attempts
                ORDER BY j.next_run_at,j.created_at,j.id
                LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """);
            if(ready.isEmpty())return Optional.<Lease>empty();
            var row=ready.get(0);UUID job=(UUID)row.get("id"),doc=(UUID)row.get("document_id"),token=UUID.randomUUID();
            int attempt=((Number)row.get("attempt_count")).intValue()+1;
            db.update("""
                UPDATE extraction_job SET status='RUNNING',attempt_count=?,lease_token=?,worker_id=?,
                  lease_until=clock_timestamp()+(? * interval '1 second'),last_heartbeat_at=clock_timestamp(),
                  started_at=COALESCE(started_at,clock_timestamp()),next_run_at=NULL,completed_at=NULL,error_code=NULL
                WHERE id=?
                """,attempt,token,workerId,config.getLeaseSeconds(),job);
            db.update("""
                INSERT INTO extraction_attempt(id,job_id,attempt_no,lease_token,worker_id,status,model,prompt_version,started_at)
                VALUES(?,?,?,?,?,'RUNNING',?,?,clock_timestamp())
                """,UUID.randomUUID(),job,attempt,token,workerId,row.get("model"),row.get("prompt_version"));
            return Optional.of(new Lease(job,(UUID)row.get("project_id"),doc,token,attempt,((Number)row.get("max_attempts")).intValue(),
                row.get("provider").toString(),row.get("model").toString(),row.get("prompt_version").toString(),input(row,doc)));
        }));
    }

    private TaskExtractor.Input input(Map<String,Object> row,UUID documentId) {
        try {
            var snapshot=json.readValue(row.get("input_snapshot_json").toString(),new TypeReference<Map<String,Object>>(){});
            @SuppressWarnings("unchecked") var tasks=(List<Map<String,Object>>)snapshot.get("tasks");
            return new TaskExtractor.Input(documentId,row.get("content").toString(),
                LocalDate.parse(row.get("reference_date").toString()),row.get("timezone").toString(),tasks);
        }catch(Exception e){throw new IllegalStateException("Invalid durable input snapshot",e);}
    }

    public boolean renew(Lease lease) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            // Never wait behind a result transaction and then resurrect an expired lease.
            var owned=db.queryForList("""
                SELECT id FROM extraction_job WHERE id=? AND status='RUNNING' AND lease_token=?
                  AND lease_until>clock_timestamp() FOR UPDATE SKIP LOCKED
                """,lease.jobId(),lease.token());
            if(owned.isEmpty())return false;
            return db.update("""
                UPDATE extraction_job SET lease_until=clock_timestamp()+(? * interval '1 second'),
                  last_heartbeat_at=clock_timestamp()
                WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
                """,config.getLeaseSeconds(),lease.jobId(),lease.token())==1;
        }));
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void execute(Lease lease) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM extraction_job WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp())",Boolean.class,lease.jobId(),lease.token())))return;
        long started=System.nanoTime();
        try {
            if(!lease.provider().equals(extractor.provider())||!lease.model().equals(extractor.model())
                ||!lease.promptVersion().equals(extractor.promptVersion()))throw new ExtractionFailure("MODEL_CONFIG_CHANGED");
            var result=extractor.extract(lease.input());
            complete(lease,result);
        }catch(LeaseLost ignored){
            // The next owner owns recovery. No write from an expired token.
        }catch(RuntimeException failure){
            String code=failure instanceof ExtractionFailure known?known.code():
                failure instanceof DataAccessException?"QUEUE_STORAGE_ERROR":"EXTRACTION_INTERNAL_ERROR";
            fail(lease,code,Math.max(0,(System.nanoTime()-started)/1_000_000));
        }
    }

    public boolean complete(Lease lease,TaskExtractor.Result result) {
        var proposals=validator.validate(lease.input(),result.output());
        try {
            tx.executeWithoutResult(ignored->{
                // Same lock order as human review: project, then job/proposal.
                db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE",lease.projectId());
                if(!owns(lease))throw new LeaseLost();
                int ordinal=0;
                for(var proposal:proposals) {
                    if(proposal.target()!=null&&db.queryForList("SELECT id FROM task WHERE id=? AND project_id=? AND archived_at IS NULL",
                        proposal.target(),lease.projectId()).isEmpty())throw new ExtractionFailure("MODEL_INVALID_TARGET");
                    var fields=new LinkedHashMap<String,Object>();
                    fields.put("title",proposal.title());fields.put("assigneeName",proposal.owner());fields.put("dueDate",proposal.dueDate());
                    var evidence=Map.of("blockId","line-"+proposal.line(),"quote",proposal.quote(),"fields",proposal.changedFields(),
                        "source","mock".equals(lease.provider())?"MOCK":"LLM");
                    db.update("""
                        INSERT INTO change_proposal(id,job_id,project_id,ordinal,operation,target_task_id,base_task_version,candidate_json,evidence_json)
                        VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                        """,UUID.randomUUID(),lease.jobId(),lease.projectId(),ordinal++,
                        proposal.target()==null?"CREATE_TASK":"UPDATE_TASK",proposal.target(),proposal.baseVersion(),encode(fields),encode(evidence));
                }
                int changed=db.update("""
                    UPDATE extraction_job SET status='SUCCEEDED',resolved_model=?,response_id=?,input_tokens=?,output_tokens=?,
                      duration_ms=?,completed_at=clock_timestamp(),output_json=?::jsonb,error_code=NULL,
                      lease_token=NULL,lease_until=NULL,worker_id=NULL
                    WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
                    """,result.resolvedModel(),result.responseId(),result.inputTokens(),result.outputTokens(),result.durationMs(),
                    encode(result.output()),lease.jobId(),lease.token());
                if(changed!=1)throw new LeaseLost(); // Also recheck expiry AFTER inserting all proposals.
                db.update("""
                    UPDATE extraction_attempt SET status='SUCCEEDED',resolved_model=?,response_id=?,input_tokens=?,output_tokens=?,
                      duration_ms=?,completed_at=clock_timestamp()
                    WHERE job_id=? AND lease_token=? AND status='RUNNING'
                    """,result.resolvedModel(),result.responseId(),result.inputTokens(),result.outputTokens(),result.durationMs(),
                    lease.jobId(),lease.token());
            });
            return true;
        }catch(LeaseLost ignored){return false;}
    }

    private boolean owns(Lease lease) {
        return !db.queryForList("""
            SELECT id FROM extraction_job WHERE id=? AND status='RUNNING' AND lease_token=?
              AND lease_until>clock_timestamp() FOR UPDATE
            """,lease.jobId(),lease.token()).isEmpty();
    }

    public boolean fail(Lease lease,String code,long durationMs) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            if(!owns(lease))return false;
            boolean retry=TRANSIENT.contains(code)&&lease.attempt()<lease.maxAttempts();
            finishAttempt(lease,"FAILED",code,durationMs);
            release(lease,retry?"RETRY_WAIT":"FAILED",code,retry?backoffMs(lease.attempt()):0);
            return true;
        }));
    }

    public boolean abandon(Lease lease) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            if(!owns(lease))return false;
            finishAttempt(lease,"ABANDONED","WORKER_STOPPED",null);
            release(lease,lease.attempt()<lease.maxAttempts()?"RETRY_WAIT":"FAILED","WORKER_STOPPED",0);
            return true;
        }));
    }

    private void finishAttempt(Lease lease,String status,String code,Long duration) {
        db.update("""
            UPDATE extraction_attempt SET status=?,error_code=?,completed_at=clock_timestamp(),
              duration_ms=COALESCE(?,GREATEST(0,(extract(epoch FROM (clock_timestamp()-started_at))*1000)::bigint))
            WHERE job_id=? AND lease_token=? AND status='RUNNING'
            """,status,code,duration,lease.jobId(),lease.token());
    }

    private void release(Lease lease,String status,String code,long delay) {
        db.update("""
            UPDATE extraction_job SET status=?,error_code=?,lease_token=NULL,lease_until=NULL,worker_id=NULL,
              next_run_at=CASE WHEN ?='RETRY_WAIT' THEN clock_timestamp()+(? * interval '1 millisecond') ELSE NULL END,
              completed_at=CASE WHEN ?='FAILED' THEN clock_timestamp() ELSE NULL END
            WHERE id=? AND status='RUNNING' AND lease_token=?
            """,status,code,status,delay,status,lease.jobId(),lease.token());
    }

    public int recoverExpired(int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Recovery batch must be between 1 and 100");
        return Objects.requireNonNull(tx.execute(ignored->{
            var rows=db.queryForList("""
                SELECT id,document_id,lease_token,attempt_count,max_attempts FROM extraction_job
                WHERE status='RUNNING' AND lease_until<=clock_timestamp()
                ORDER BY lease_until,id LIMIT ? FOR UPDATE SKIP LOCKED
                """,limit);
            for(var row:rows) {
                var lease=new Lease((UUID)row.get("id"),null,(UUID)row.get("document_id"),(UUID)row.get("lease_token"),
                    ((Number)row.get("attempt_count")).intValue(),((Number)row.get("max_attempts")).intValue(),null,null,null,null);
                finishAttempt(lease,"EXPIRED","LEASE_EXPIRED",null);
                boolean retry=lease.attempt()<lease.maxAttempts();
                release(lease,retry?"RETRY_WAIT":"FAILED","LEASE_EXPIRED",retry?backoffMs(lease.attempt()):0);
            }
            return rows.size();
        }));
    }

    long backoffMs(int attempt) {
        long base=Math.min(config.getRetryMaxMs(),config.getRetryBaseMs()*(1L<<Math.min(20,Math.max(0,attempt-1))));
        long jitter=ThreadLocalRandom.current().nextLong(Math.max(1,base/4+1));
        return Math.min(config.getRetryMaxMs(),base+jitter);
    }
    private static final class LeaseLost extends RuntimeException {}
}
