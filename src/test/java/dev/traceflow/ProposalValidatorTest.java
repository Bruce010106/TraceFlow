package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalValidatorTest {
    final ObjectMapper json=new ObjectMapper();final ProposalValidator validator=new ProposalValidator();
    final UUID task=UUID.randomUUID();
    TaskExtractor.Input input(){
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("id",task);snapshot.put("title","接口联调");
        snapshot.put("assignee_name","王敏");snapshot.put("due_date",LocalDate.of(2026,11,12));snapshot.put("version",3L);
        return new TaskExtractor.Input(UUID.randomUUID(),"请李明完成接口联调。\n把接口联调延期到2026-11-15。",LocalDate.of(2026,11,11),"Asia/Hong_Kong",List.of(snapshot));
    }
    ObjectNode create(){
        var item=json.createObjectNode();item.put("operation","CREATE_TASK");item.putNull("targetTaskId");item.put("title","接口联调");
        item.put("assigneeName","李明");item.putNull("dueDate");item.putArray("changedFields").add("title").add("assigneeName");
        item.putObject("evidence").put("line",1).put("quote","请李明完成接口联调。");return item;
    }
    ObjectNode root(ObjectNode...items){var root=json.createObjectNode();var array=root.putArray("proposals");for(var item:items)array.add(item);return root;}
    String invalid(JsonNode data){return assertThrows(ExtractionFailure.class,()->validator.validate(input(),data)).code();}
    @Test void createsOnlyPendingDataAndKeepsUnknownDateNull(){
        var item=validator.validate(input(),root(create())).get(0);assertNull(item.target());assertNull(item.dueDate());assertEquals("李明",item.owner());
    }
    @Test void updateIsAPatchAgainstCapturedSnapshot(){
        var item=create();item.put("operation","UPDATE_TASK");item.put("targetTaskId",task.toString());
        item.putNull("title");item.putNull("assigneeName");item.put("dueDate","2026-11-15");item.putArray("changedFields").add("dueDate");
        item.putObject("evidence").put("line",2).put("quote","延期到2026-11-15");
        var proposal=validator.validate(input(),root(item)).get(0);
        assertEquals(3L,proposal.baseVersion());assertEquals("王敏",proposal.owner());assertEquals("接口联调",proposal.title());
        assertEquals(LocalDate.of(2026,11,15),proposal.dueDate());
    }
    @Test void rejectsInventedOrCrossProjectTaskIds(){
        var item=create();item.put("operation","UPDATE_TASK");item.put("targetTaskId",UUID.randomUUID().toString());
        assertEquals("MODEL_INVALID_TARGET",invalid(root(item)));
    }
    @Test void requiresVerbatimQuoteAtValidLine(){
        var item=create();item.withObject("/evidence").put("quote","虚构引句");
        assertEquals("MODEL_INVALID_EVIDENCE",invalid(root(item)));
        item=create();item.withObject("/evidence").put("line",0);assertEquals("MODEL_INVALID_EVIDENCE",invalid(root(item)));
        item=create();item.withObject("/evidence").put("line",2);assertEquals("MODEL_INVALID_EVIDENCE",invalid(root(item)));
    }
    @Test void rejectsUnknownFieldsStatusChangesAndInvalidDates(){
        var item=create();item.put("status","DONE");assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
        item=create();item.putArray("changedFields").add("status");assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
        item=create();item.put("dueDate","2026-02-30");assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
    }
    @Test void rejectsDuplicatesAndOversizedBatches(){
        assertEquals("MODEL_DUPLICATE_PROPOSAL",invalid(root(create(),create())));
        var root=root();for(int i=0;i<21;i++)((ArrayNode)root.get("proposals")).add(create());
        assertEquals("MODEL_INVALID_OUTPUT",invalid(root));
    }
    @Test void rejectsMissingKeysLongFieldsAndInvalidNewTaskTargets(){
        var item=create();item.remove("dueDate");assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
        item=create();item.put("title","x".repeat(201));assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
        item=create();item.put("assigneeName","x".repeat(121));assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
        item=create();item.put("targetTaskId",task.toString());assertEquals("MODEL_INVALID_OUTPUT",invalid(root(item)));
    }
    @Test void acceptsAnEmptyResult(){assertTrue(validator.validate(input(),root()).isEmpty());}
}
