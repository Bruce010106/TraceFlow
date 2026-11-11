package dev.traceflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit offline fixture only. The production default is the LLM adapter. */
public class MockExtractor implements TaskExtractor {
    private static final Pattern CREATE=Pattern.compile("新增任务：([^；]+)；负责人：([^；]*)；截止：(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern UPDATE=Pattern.compile("更新任务：([0-9a-fA-F-]{36})；负责人：([^；]*)；截止：(\\d{4}-\\d{2}-\\d{2})");
    private final ObjectMapper json;
    public MockExtractor(ObjectMapper json){this.json=json;}
    public String mode(){return "mock";}
    public String model(){return "mock-v1";}
    public String promptVersion(){return "mock-v1";}
    public boolean configured(){return true;}
    public Result extract(Input input) {
        var out=new ArrayList<Map<String,Object>>(); String[] lines=input.content().split("\\n",-1);
        for(int i=0;i<lines.length;i++) {
            String quote=lines[i].strip(); var create=CREATE.matcher(quote);var update=UPDATE.matcher(quote);
            try {
                if(!create.matches()&&!update.matches()) continue;
                boolean isNew=create.matches();
                var matcher=isNew?create:update;
                LocalDate due=LocalDate.parse(matcher.group(3));
                String target=isNew?null:UUID.fromString(matcher.group(1)).toString();
                var item=new LinkedHashMap<String,Object>();
                item.put("operation",isNew?"CREATE_TASK":"UPDATE_TASK");item.put("targetTaskId",target);
                item.put("title",isNew?matcher.group(1).strip():null);
                item.put("assigneeName",matcher.group(2).strip().isEmpty()?null:matcher.group(2).strip());
                item.put("dueDate",due.toString());
                item.put("changedFields",isNew?List.of("title","assigneeName","dueDate"):List.of("assigneeName","dueDate"));
                item.put("evidence",Map.of("line",i+1,"quote",quote));out.add(item);
            } catch(IllegalArgumentException | java.time.DateTimeException e) {
                throw new ExtractionFailure("INVALID_MOCK_INPUT");
            }
        }
        if(out.size()>20) throw new ExtractionFailure("TOO_MANY_PROPOSALS");
        return new Result(json.valueToTree(Map.of("proposals",out)),model(),null,null,null,0);
    }
}
