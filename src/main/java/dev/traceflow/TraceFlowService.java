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
    private final ExtractionQueue queue;
    private final QueueProperties queueConfig;
    public TraceFlowService(JdbcTemplate db,ObjectMapper json,TaskExtractor extractor,ExtractionQueue queue,QueueProperties queueConfig) {
        this.db=db;this.json=json;this.extractor=extractor;this.queue=queue;this.queueConfig=queueConfig;
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
              (SELECT count(*) FROM change_proposal c WHERE c.project_id=p.id AND c.status='PENDING') AS pending_count,
              (SELECT count(*) FROM extraction_job j JOIN source_document d ON d.id=j.document_id WHERE d.project_id=p.id AND j.status IN ('QUEUED','RUNNING','RETRY_WAIT')) AS active_job_count
            FROM project p ORDER BY p.created_at,p.id
            """);
    }

    /** Only durable enqueueing happens in this request transaction. */
    @Transactional
    public Map<String,Object> importDocument(UUID projectId,DocumentInput input) {
        lockProject(projectId);
        var project=one("SELECT * FROM project WHERE id=?",projectId);
        String content=input.content().replace("\r\n","\n").replace("\r","\n");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
        var existing=db.queryForList("SELECT id FROM source_document WHERE project_id=? AND content_hash=? AND reference_date=? AND timezone=?",
            projectId,hash,input.referenceDate(),project.get("timezone"));
        if(!existing.isEmpty()){
            var result=document(uuid(existing.get(0).get("id")));result.put("duplicate",true);return result;
        }
        UUID doc=UUID.randomUUID();
        db.update("INSERT INTO source_document(id,project_id,title,content,content_hash,reference_date,timezone) VALUES(?,?,?,?,?,?,?)",
            doc,projectId,input.title().strip(),content,hash,input.referenceDate(),project.get("timezone"));
        queue.enqueue(projectId,doc,input.referenceDate(),project.get("timezone").toString(),1);
        var result=document(doc);result.put("duplicate",false);return result;
    }

    /** Manual retry starts a new generation only after the previous one is terminal FAILED. */
    @Transactional
    public Map<String,Object> retryExtraction(UUID documentId) {
        UUID projectId=uuid(one("SELECT project_id FROM source_document WHERE id=?",documentId).get("project_id"));
        lockProject(projectId);
        var source=one("SELECT * FROM source_document WHERE id=?",documentId);
        var latest=one("SELECT * FROM extraction_job WHERE document_id=? ORDER BY generation DESC LIMIT 1 FOR UPDATE",documentId);
        boolean duplicate=!"FAILED".equals(latest.get("status"));
        if(!duplicate)queue.enqueue(projectId,documentId,LocalDate.parse(source.get("reference_date").toString()),
            source.get("timezone").toString(),((Number)latest.get("generation")).intValue()+1);
        var result=document(documentId);result.put("duplicate",duplicate);return result;
    }

    public Map<String,Object> extractorInfo() {
        return Map.of("provider",extractor.provider(),"extractor",extractor.mode(),"model",extractor.model(),
            "configured",extractor.configured(),"promptVersion",extractor.promptVersion(),"queueEnabled",queueConfig.isEnabled());
    }

    public List<Map<String,Object>> documents(UUID projectId,int page,int size) {
        one("SELECT id FROM project WHERE id=?",projectId);
        if(page<0||size<1||size>100) throw new ApiException(400,"INVALID_PAGE","page从0开始，size为1到100");
        return db.queryForList("""
            SELECT d.id,d.project_id,d.title,d.reference_date,d.timezone,d.created_at,
                   count(p.id) AS proposal_count,
                   count(p.id) FILTER (WHERE p.status='PENDING') AS pending_count,
                   (SELECT j.status FROM extraction_job j WHERE j.document_id=d.id ORDER BY j.generation DESC LIMIT 1) AS extraction_status,
                   (SELECT j.error_code FROM extraction_job j WHERE j.document_id=d.id ORDER BY j.generation DESC LIMIT 1) AS extraction_error
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
        var jobs=db.queryForList("SELECT * FROM extraction_job WHERE document_id=? ORDER BY generation",id).stream().map(this::view).toList();
        for(var job:jobs){job.remove("lease_token");job.put("attempts",db.queryForList("SELECT attempt_no,status,error_code,started_at,completed_at,duration_ms,model,resolved_model,input_tokens,output_tokens FROM extraction_attempt WHERE job_id=? ORDER BY attempt_no",job.get("id")));}
        result.put("jobs",jobs);
        result.put("proposals",db.queryForList("SELECT p.* FROM change_proposal p JOIN extraction_job j ON j.id=p.job_id WHERE j.document_id=? ORDER BY j.generation,p.ordinal",id).stream().map(this::view).toList());
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
