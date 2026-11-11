package dev.traceflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static dev.traceflow.Contracts.*;

@Service
public class TraceFlowService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TaskExtractor extractor;
    private final ProposalValidator proposalValidator;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public TraceFlowService(JdbcTemplate db,ObjectMapper json,TaskExtractor extractor,ProposalValidator proposalValidator,org.springframework.transaction.PlatformTransactionManager manager) {
        this.db=db; this.json=json; this.extractor=extractor; this.proposalValidator=proposalValidator;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw ApiException.missing("记录");
        return rows.get(0);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("JSON serialization failed",e); }
    }
    private Object decode(Object value) {
        if(value==null) return null;
        try { return json.readValue(value.toString(),Object.class); }
        catch(Exception e) { throw new IllegalStateException("JSON parsing failed",e); }
    }
    private Map<String,Object> view(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);
        result.replaceAll((key,value)->key.endsWith("_json")?decode(value):value);
        return result;
    }
    private Map<String,Object> fields(String title,String owner,LocalDate date) {
        var result=new LinkedHashMap<String,Object>();
        result.put("title",title.strip()); result.put("assigneeName",owner); result.put("dueDate",date);
        return result;
    }
    private void lockProject(UUID id) { one("SELECT * FROM project WHERE id=? FOR UPDATE",id); }
    private UUID uuid(Object value) { return UUID.fromString(value.toString()); }
    private void history(UUID taskId,UUID proposal,Map<String,Object> before,Map<String,Object> after,String actor) {
        db.update("INSERT INTO task_change(id,task_id,proposal_id,actor_type,before_json,after_json) VALUES(?,?,?,?,?::jsonb,?::jsonb)",
            UUID.randomUUID(),taskId,proposal,actor,before==null?null:encode(before),encode(after));
    }
    @Transactional
    public Map<String,Object> createProject(ProjectInput input) {
        try { ZoneId.of(input.timezone()); } catch(DateTimeException e) {
            throw new ApiException(400,"INVALID_TIMEZONE","无效的时区");
        }
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO project(id,name,timezone) VALUES(?,?,?)",id,input.name().strip(),input.timezone());
        return one("SELECT * FROM project WHERE id=?",id);
    }
    public List<Map<String,Object>> projects() {
        return db.queryForList("""
            SELECT p.*,
              (SELECT count(*) FROM source_document d WHERE d.project_id=p.id) AS document_count,
              (SELECT count(*) FROM task t WHERE t.project_id=p.id AND t.archived_at IS NULL) AS task_count,
              (SELECT count(*) FROM change_proposal c WHERE c.project_id=p.id AND c.status='PENDING') AS pending_count
            FROM project p ORDER BY p.created_at,p.id
            """);
    }

    private record ExtractionWork(UUID projectId,UUID documentId,UUID jobId,TaskExtractor.Input input,boolean duplicate) {}

    /** Short DB transaction -> bounded network call -> short DB transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String,Object> importDocument(UUID projectId,DocumentInput input) {
        var work=transactions.execute(ignored->{
            lockProject(projectId);
            var project=one("SELECT * FROM project WHERE id=?",projectId);
            String content=input.content().replace("\r\n","\n").replace("\r","\n");
            String hash;
            try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
            catch(Exception e){throw new IllegalStateException(e);}
            var existing=db.queryForList("SELECT id FROM source_document WHERE project_id=? AND content_hash=? AND reference_date=? AND timezone=?",
                projectId,hash,input.referenceDate(),project.get("timezone"));
            if(!existing.isEmpty())return new ExtractionWork(projectId,uuid(existing.get(0).get("id")),null,null,true);
            UUID doc=UUID.randomUUID();
            db.update("INSERT INTO source_document(id,project_id,title,content,content_hash,reference_date,timezone) VALUES(?,?,?,?,?,?,?)",
                doc,projectId,input.title().strip(),content,hash,input.referenceDate(),project.get("timezone"));
            return startExtraction(projectId,doc,content,input.referenceDate(),project.get("timezone").toString(),1);
        });
        return runExtraction(Objects.requireNonNull(work));
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String,Object> retryExtraction(UUID documentId) {
        var work=transactions.execute(ignored->{
            var initial=one("SELECT project_id FROM source_document WHERE id=?",documentId);
            UUID projectId=uuid(initial.get("project_id"));
            lockProject(projectId);
            var source=one("SELECT * FROM source_document WHERE id=?",documentId);
            var latest=one("SELECT * FROM extraction_job WHERE document_id=? ORDER BY attempt_count DESC,created_at DESC,id DESC LIMIT 1",documentId);
            if(!"FAILED".equals(latest.get("status")))return new ExtractionWork(projectId,documentId,null,null,true);
            return startExtraction(projectId,documentId,source.get("content").toString(),
                LocalDate.parse(source.get("reference_date").toString()),source.get("timezone").toString(),
                ((Number)latest.get("attempt_count")).intValue()+1);
        });
        return runExtraction(Objects.requireNonNull(work));
    }

    private ExtractionWork startExtraction(UUID projectId,UUID doc,String content,LocalDate referenceDate,String timezone,int attempt) {
        var tasks=db.queryForList("SELECT id,title,assignee_name,due_date,status,version FROM task WHERE project_id=? AND archived_at IS NULL ORDER BY created_at,id",projectId);
        if(tasks.size()>50)throw new ApiException(400,"TASK_LIMIT","项目未归档任务超过50条");
        UUID job=UUID.randomUUID();
        var snapshot=Map.of("documentId",doc,"referenceDate",referenceDate,"timezone",timezone,"tasks",tasks);
        db.update("""
            INSERT INTO extraction_job(id,document_id,status,attempt_count,model,prompt_version,input_snapshot_json,provider)
            VALUES(?,?,'RUNNING',?,?,?,?::jsonb,?)
            """,job,doc,attempt,extractor.model(),extractor.promptVersion(),encode(snapshot),
            extractor.provider());
        return new ExtractionWork(projectId,doc,job,new TaskExtractor.Input(doc,content,referenceDate,timezone,tasks),false);
    }

    private Map<String,Object> runExtraction(ExtractionWork work) {
        if(work.jobId()!=null) {
            long started=System.nanoTime();
            try {
                var result=extractor.extract(work.input());
                var proposals=proposalValidator.validate(work.input(),result.output());
                transactions.executeWithoutResult(ignored->{
                    lockProject(work.projectId());
                    var job=one("SELECT status FROM extraction_job WHERE id=? FOR UPDATE",work.jobId());
                    if(!"RUNNING".equals(job.get("status")))throw new ExtractionFailure("EXTRACTION_ALREADY_FINISHED");
                    int ordinal=0;
                    for(var proposal:proposals) {
                        if(proposal.target()!=null) {
                            var target=db.queryForList("SELECT id FROM task WHERE id=? AND project_id=? AND archived_at IS NULL",
                                proposal.target(),work.projectId());
                            if(target.isEmpty())throw new ExtractionFailure("MODEL_INVALID_TARGET");
                        }
                        var evidence=Map.of("blockId","line-"+proposal.line(),"quote",proposal.quote(),
                            "fields",proposal.changedFields(),"source","mock".equals(extractor.mode())?"MOCK":"LLM");
                        db.update("""
                            INSERT INTO change_proposal(id,job_id,project_id,ordinal,operation,target_task_id,base_task_version,candidate_json,evidence_json)
                            VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                            """,UUID.randomUUID(),work.jobId(),work.projectId(),ordinal++,
                            proposal.target()==null?"CREATE_TASK":"UPDATE_TASK",proposal.target(),proposal.baseVersion(),
                            encode(fields(proposal.title(),proposal.owner(),proposal.dueDate())),encode(evidence));
                    }
                    db.update("""
                        UPDATE extraction_job SET status='SUCCEEDED',resolved_model=?,response_id=?,input_tokens=?,output_tokens=?,
                          duration_ms=?,completed_at=now(),output_json=?::jsonb,error_code=NULL WHERE id=?
                        """,result.resolvedModel(),result.responseId(),result.inputTokens(),result.outputTokens(),
                        result.durationMs(),encode(result.output()),work.jobId());
                });
            } catch(RuntimeException failure) {
                String code=failure instanceof ExtractionFailure known?known.code():"EXTRACTION_INTERNAL_ERROR";
                long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                transactions.executeWithoutResult(ignored->db.update("""
                    UPDATE extraction_job SET status='FAILED',error_code=?,duration_ms=?,completed_at=now()
                    WHERE id=? AND status='RUNNING'
                    """,code,elapsed,work.jobId()));
            }
        }
        var result=document(work.documentId());result.put("duplicate",work.duplicate());return result;
    }

    public Map<String,Object> extractorInfo() {
        return Map.of("provider",extractor.provider(),"extractor",extractor.mode(),"model",extractor.model(),
            "configured",extractor.configured(),"promptVersion",extractor.promptVersion());
    }

    public List<Map<String,Object>> documents(UUID projectId,int page,int size) {
        one("SELECT id FROM project WHERE id=?",projectId);
        if(page<0||size<1||size>100) throw new ApiException(400,"INVALID_PAGE","page从0开始，size为1到100");
        return db.queryForList("""
            SELECT d.id,d.project_id,d.title,d.reference_date,d.timezone,d.created_at,
                   count(p.id) AS proposal_count,
                   count(p.id) FILTER (WHERE p.status='PENDING') AS pending_count,
                   (SELECT j.status FROM extraction_job j WHERE j.document_id=d.id ORDER BY j.attempt_count DESC,j.created_at DESC,j.id DESC LIMIT 1) AS extraction_status,
                   (SELECT j.error_code FROM extraction_job j WHERE j.document_id=d.id ORDER BY j.attempt_count DESC,j.created_at DESC,j.id DESC LIMIT 1) AS extraction_error
            FROM source_document d
            LEFT JOIN extraction_job j ON j.document_id=d.id
            LEFT JOIN change_proposal p ON p.job_id=j.id
            WHERE d.project_id=?
            GROUP BY d.id
            ORDER BY d.created_at DESC,d.id
            LIMIT ? OFFSET ?
            """,projectId,size,(long)page*size);
    }

    public Map<String,Object> document(UUID id) {
        var result=view(one("SELECT * FROM source_document WHERE id=?",id));
        result.put("jobs",db.queryForList("SELECT * FROM extraction_job WHERE document_id=? ORDER BY attempt_count,created_at,id",id).stream().map(this::view).toList());
        result.put("proposals",db.queryForList("SELECT p.* FROM change_proposal p JOIN extraction_job j ON j.id=p.job_id WHERE j.document_id=? ORDER BY j.attempt_count,j.created_at,p.ordinal",id).stream().map(this::view).toList());
        return result;
    }
    private Map<String,Object> lockProposal(UUID id) {
        var initial=one("SELECT project_id FROM change_proposal WHERE id=?",id);
        lockProject(uuid(initial.get("project_id")));
        return one("SELECT * FROM change_proposal WHERE id=? FOR UPDATE",id);
    }
    @Transactional
    public Map<String,Object> approve(UUID id,ApprovalInput input) {
        var proposal=lockProposal(id);
        if("APPLIED".equals(proposal.get("status")))
            return (Map<String,Object>)decode(one("SELECT after_json FROM task_change WHERE proposal_id=?",id).get("after_json"));
        if(!"PENDING".equals(proposal.get("status")))
            throw new ApiException(409,"PROPOSAL_CLOSED","建议已拒绝");
        UUID projectId=uuid(proposal.get("project_id"));
        Map<String,Object> before=null;
        UUID taskId;
        if("CREATE_TASK".equals(proposal.get("operation"))) {
            if(input.expectedTaskVersion()!=null) throw new ApiException(400,"INVALID_INPUT","新任务不接受已有任务版本");
            int count=db.queryForObject("SELECT count(*) FROM task WHERE project_id=? AND archived_at IS NULL",Integer.class,projectId);
            if(count>=50) throw new ApiException(409,"TASK_LIMIT","项目未归档任务最多50条");
            taskId=UUID.randomUUID();
            db.update("INSERT INTO task(id,project_id,title,assignee_name,due_date) VALUES(?,?,?,?,?)",
                taskId,projectId,input.title().strip(),input.assigneeName(),input.dueDate());
        } else {
            taskId=uuid(proposal.get("target_task_id"));
            before=one("SELECT * FROM task WHERE id=? AND project_id=? FOR UPDATE",taskId,projectId);
            if(before.get("archived_at")!=null) throw new ApiException(409,"TASK_ARCHIVED","任务已归档");
            if(input.expectedTaskVersion()==null) throw new ApiException(400,"VERSION_REQUIRED","更新任务必须提供审核时看到的版本");
            int changed=db.update("UPDATE task SET title=?,assignee_name=?,due_date=?,version=version+1,updated_at=now() WHERE id=? AND version=?",
                input.title().strip(),input.assigneeName(),input.dueDate(),taskId,input.expectedTaskVersion());
            if(changed!=1) throw ApiException.conflict("任务已经修改，请刷新差异后重新确认");
        }
        var after=one("SELECT * FROM task WHERE id=?",taskId);
        history(taskId,id,before,after,"REVIEWER");
        db.update("UPDATE change_proposal SET status='APPLIED',applied_task_id=?,reviewed_json=?::jsonb,reviewed_at=now() WHERE id=?",
            taskId,encode(input),id);
        return after;
    }
    @Transactional
    public Map<String,Object> reject(UUID id) {
        var row=lockProposal(id);
        if("APPLIED".equals(row.get("status"))) throw new ApiException(409,"PROPOSAL_CLOSED","建议已经应用");
        db.update("UPDATE change_proposal SET status='REJECTED',reviewed_at=COALESCE(reviewed_at,now()) WHERE id=?",id);
        return view(one("SELECT * FROM change_proposal WHERE id=?",id));
    }
    public Map<String,Object> task(UUID id) { return one("SELECT * FROM task WHERE id=?",id); }
    @Transactional
    public Map<String,Object> editTask(UUID id,TaskInput input) {
        var initial=task(id);
        lockProject(uuid(initial.get("project_id")));
        var before=one("SELECT * FROM task WHERE id=? FOR UPDATE",id);
        int changed=db.update("UPDATE task SET title=?,assignee_name=?,due_date=?,status=?,version=version+1,updated_at=now() WHERE id=? AND version=?",
            input.title().strip(),input.assigneeName(),input.dueDate(),input.status().name(),id,input.expectedTaskVersion());
        if(changed!=1) throw ApiException.conflict("任务版本已变化");
        var after=task(id); history(id,null,before,after,"MANUAL"); return after;
    }
    public List<Map<String,Object>> tasks(UUID project,int page,int size) {
        one("SELECT id FROM project WHERE id=?",project);
        if(page<0||size<1||size>100) throw new ApiException(400,"INVALID_PAGE","page从0开始，size为1到100");
        return db.queryForList("SELECT * FROM task WHERE project_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",project,size,(long)page*size);
    }
    public List<Map<String,Object>> history(UUID id) {
        task(id);
        return db.queryForList("SELECT h.*,j.document_id,p.evidence_json FROM task_change h LEFT JOIN change_proposal p ON p.id=h.proposal_id LEFT JOIN extraction_job j ON j.id=p.job_id WHERE h.task_id=? ORDER BY h.created_at,h.id",id)
            .stream().map(this::view).toList();
    }
}
