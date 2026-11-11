package dev.traceflow;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static dev.traceflow.Contracts.*;

@RestController
@RequestMapping("/api")
public class TraceFlowController {
    private final TraceFlowService service;
    public TraceFlowController(TraceFlowService service) { this.service=service; }
    @GetMapping("/health") public Map<String,Object> health() { var result=new LinkedHashMap<String,Object>(service.extractorInfo());result.put("status","UP");return result; }
    @PostMapping("/projects") public Object createProject(@Valid @RequestBody ProjectInput input) { return service.createProject(input); }
    @GetMapping("/projects") public Object projects() { return service.projects(); }
    @PostMapping("/projects/{id}/documents") public Object importDocument(@PathVariable UUID id,@Valid @RequestBody DocumentInput input) { return org.springframework.http.ResponseEntity.accepted().body(service.importDocument(id,input)); }
    @GetMapping("/projects/{id}/documents") public Object documents(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.documents(id,page,size); }
    @PostMapping("/documents/{id}/extract") public Object retryExtraction(@PathVariable UUID id) { return org.springframework.http.ResponseEntity.accepted().body(service.retryExtraction(id)); }
    @GetMapping("/documents/{id}") public Object document(@PathVariable UUID id) { return service.document(id); }
    @PostMapping("/proposals/{id}/approve") public Object approve(@PathVariable UUID id,@Valid @RequestBody ApprovalInput input) { return service.approve(id,input); }
    @PostMapping("/proposals/{id}/reject") public Object reject(@PathVariable UUID id) { return service.reject(id); }
    @GetMapping("/projects/{id}/tasks") public Object tasks(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.tasks(id,page,size); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable UUID id) { return service.task(id); }
    @PatchMapping("/tasks/{id}") public Object editTask(@PathVariable UUID id,@Valid @RequestBody TaskInput input) { return service.editTask(id,input); }
    @GetMapping("/tasks/{id}/history") public Object history(@PathVariable UUID id) { return service.history(id); }
}
