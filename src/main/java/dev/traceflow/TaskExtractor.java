package dev.traceflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TaskExtractor {
    record Input(UUID documentId, String content, LocalDate referenceDate,
                 String timezone, List<Map<String,Object>> tasks) {}
    record Result(JsonNode output, String resolvedModel, String responseId,
                  Integer inputTokens, Integer outputTokens, long durationMs) {}
    String mode();
    default String provider(){return mode();}
    String model();
    String promptVersion();
    boolean configured();
    Result extract(Input input);
}
