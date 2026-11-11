package dev.traceflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic boundary between untrusted model output and pending proposals. */
@Component
public class ProposalValidator {
    private static final Set<String> FIELDS=Set.of("title","assigneeName","dueDate");
    public record Proposal(UUID target,Long baseVersion,String title,String owner,LocalDate dueDate,
                           List<String> changedFields,int line,String quote) {}
    public List<Proposal> validate(TaskExtractor.Input input,JsonNode root) {
        keys(root,Set.of("proposals"));
        JsonNode items=root.get("proposals");
        if(!items.isArray()||items.size()>20) fail("MODEL_INVALID_OUTPUT");
        var snapshots=new HashMap<UUID,Map<String,Object>>();
        for(var task:input.tasks()) snapshots.put(UUID.fromString(task.get("id").toString()),task);
        String[] lines=input.content().split("\\n",-1);
        var result=new ArrayList<Proposal>();
        var seen=new HashSet<String>();
        for(var item:items) {
            keys(item,Set.of("operation","targetTaskId","title","assigneeName","dueDate","changedFields","evidence"));
            String operation=text(item.get("operation"),false,20);
            if(!Set.of("CREATE_TASK","UPDATE_TASK").contains(operation)) fail("MODEL_INVALID_OUTPUT");
            String targetText=text(item.get("targetTaskId"),true,36);
            String title=text(item.get("title"),true,200);
            String owner=text(item.get("assigneeName"),true,120);
            String due=text(item.get("dueDate"),true,10);
            LocalDate dueDate=null;
            if(due!=null) {
                if(!due.matches("\\d{4}-\\d{2}-\\d{2}")) fail("MODEL_INVALID_OUTPUT");
                try {dueDate=LocalDate.parse(due);} catch(Exception e){fail("MODEL_INVALID_OUTPUT");}
            }
            JsonNode changed=item.get("changedFields");
            if(!changed.isArray()||changed.isEmpty()||changed.size()>3) fail("MODEL_INVALID_OUTPUT");
            var fields=new LinkedHashSet<String>();
            for(var field:changed) {
                if(!field.isTextual()||!FIELDS.contains(field.asText())||!fields.add(field.asText())) fail("MODEL_INVALID_OUTPUT");
            }
            JsonNode evidence=item.get("evidence");keys(evidence,Set.of("line","quote"));
            if(!evidence.get("line").isIntegralNumber()||!evidence.get("line").canConvertToInt()) fail("MODEL_INVALID_EVIDENCE");
            int line=evidence.get("line").asInt();String quote=text(evidence.get("quote"),false,1000);
            if(quote.isBlank()||line<1||line>lines.length||!lines[line-1].contains(quote)) fail("MODEL_INVALID_EVIDENCE");
            UUID target=null;Long version=null;
            if("CREATE_TASK".equals(operation)) {
                if(targetText!=null||title==null||title.isBlank()||!fields.contains("title")) fail("MODEL_INVALID_OUTPUT");
            } else {
                try{target=UUID.fromString(Objects.requireNonNull(targetText));}catch(Exception e){fail("MODEL_INVALID_TARGET");}
                var snapshot=snapshots.get(target);
                if(snapshot==null) fail("MODEL_INVALID_TARGET");
                version=((Number)snapshot.get("version")).longValue();
                if(!fields.contains("title"))title=snapshot.get("title").toString();
                if(!fields.contains("assigneeName"))owner=(String)snapshot.get("assignee_name");
                if(!fields.contains("dueDate"))dueDate=snapshot.get("due_date")==null?null:LocalDate.parse(snapshot.get("due_date").toString());
                if(title==null||title.isBlank()) fail("MODEL_INVALID_OUTPUT");
            }
            String identity=operation+"|"+target+"|"+title+"|"+owner+"|"+dueDate+"|"+line+"|"+quote;
            if(!seen.add(identity)) fail("MODEL_DUPLICATE_PROPOSAL");
            result.add(new Proposal(target,version,title.strip(),owner,dueDate,List.copyOf(fields),line,quote));
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode node,boolean nullable,int max) {
        if(node!=null&&node.isNull()&&nullable)return null;
        if(node==null||!node.isTextual()||node.textValue().length()>max) {fail("MODEL_INVALID_OUTPUT");return null;}
        return node.textValue();
    }
    private static void keys(JsonNode node,Set<String> expected) {
        if(node==null||!node.isObject()||node.size()!=expected.size())fail("MODEL_INVALID_OUTPUT");
        var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))fail("MODEL_INVALID_OUTPUT");
    }
    private static void fail(String code){throw new ExtractionFailure(code);}
}
