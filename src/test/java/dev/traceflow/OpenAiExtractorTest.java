package dev.traceflow;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiExtractorTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server; ExecutorService pool;
    final AtomicReference<String> body=new AtomicReference<>(),authorization=new AtomicReference<>();
    final AtomicInteger calls=new AtomicInteger();
    volatile int status=200,delayMs=0;
    volatile String response;
    @BeforeEach void start() throws Exception {
        response=envelope("{\"proposals\":[]}","stop");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status,bytes.length);
                if(delayMs>0){exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();Thread.sleep(delayMs);exchange.getResponseBody().write(bytes,1,bytes.length-1);}
                else exchange.getResponseBody().write(bytes);
            } catch(Exception ignored){} finally{exchange.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);pool.shutdownNow();}
    AiProperties properties(){
        var p=new AiProperties();p.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        p.setModel("wire-test");p.setApiKey("test-only-secret");p.setThinkingMode("provider_default");return p;
    }
    TaskExtractor.Input input(){return new TaskExtractor.Input(UUID.randomUUID(),"李明负责整理文档。\n次日评审。",java.time.LocalDate.of(2026,11,11),"Asia/Hong_Kong",List.of());}
    TaskExtractor.Result extract(){return new OpenAiExtractor(properties(),json).extract(input());}
    String envelope(String output,String finish)throws Exception {
        return json.writeValueAsString(Map.of("id","wire-1","model","resolved-model","usage",Map.of("prompt_tokens",10,"completion_tokens",20),
            "choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",output)))));
    }
    @Test void compatibleJsonModeSendsContextAndCapturesUsage()throws Exception{
        var p=properties();p.setThinkingMode("disabled");
        var result=new OpenAiExtractor(p,json).extract(input());
        var sent=json.readTree(body.get());
        assertEquals("Bearer test-only-secret",authorization.get());
        assertEquals("json_object",sent.path("response_format").path("type").asText());
        assertTrue(sent.has("max_tokens"));assertEquals("disabled",sent.path("thinking").path("type").asText());
        assertTrue(sent.path("messages").get(0).path("content").asText().contains("JSON schema"));
        var context=json.readTree(sent.path("messages").get(1).path("content").asText());
        assertEquals(2,context.path("documentLines").size());
        assertEquals("2026-11-11",context.path("referenceDate").asText());
        assertEquals("resolved-model",result.resolvedModel());assertEquals(10,result.inputTokens());assertEquals(20,result.outputTokens());
    }
    @Test void strictSchemaIsSelectableWithoutProviderSpecificThinking()throws Exception{
        var p=properties();p.setResponseFormat("json_schema");p.setTokenLimitField("max_completion_tokens");
        new OpenAiExtractor(p,json).extract(input());var sent=json.readTree(body.get());
        assertTrue(sent.path("response_format").path("json_schema").path("strict").asBoolean());
        assertTrue(sent.has("max_completion_tokens"));assertFalse(sent.has("thinking"));
    }
    @Test void rejectsMalformedFencedTrailingAndDuplicateJson()throws Exception{
        for(String value:List.of("bad","~~~json\n{}\n~~~","{\"proposals\":[]} {}","{\"proposals\":[],\"proposals\":[]}")){
            response=envelope(value,"stop");
            assertEquals("MODEL_INVALID_OUTPUT",assertThrows(ExtractionFailure.class,this::extract).code());
        }
    }
    @Test void rejectsBlankAndTruncatedOutput()throws Exception{
        response=envelope("   ","stop");assertEquals("MODEL_INVALID_OUTPUT",assertThrows(ExtractionFailure.class,this::extract).code());
        response=envelope("{\"proposals\":[]}","length");assertEquals("MODEL_INCOMPLETE",assertThrows(ExtractionFailure.class,this::extract).code());
    }
    @Test void handlesRefusal()throws Exception{
        response=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("refusal","cannot comply","content","")))));
        assertEquals("MODEL_REFUSED",assertThrows(ExtractionFailure.class,this::extract).code());
    }
    @Test void safeProviderErrorsHaveNoHiddenRetries(){
        for(var entry:Map.of(401,"MODEL_AUTH_ERROR",429,"MODEL_RATE_LIMITED",503,"MODEL_UNAVAILABLE",400,"MODEL_REQUEST_REJECTED",302,"MODEL_REQUEST_REJECTED").entrySet()){
            status=entry.getKey();response="private-provider-detail";
            assertEquals(entry.getValue(),assertThrows(ExtractionFailure.class,this::extract).code());
        }
        assertEquals(5,calls.get());
    }
    @Test void boundsWholeResponseIncludingSlowBody(){
        delayMs=5000;var p=properties();p.setTimeoutSeconds(1);
        long started=System.nanoTime();
        assertEquals("MODEL_TIMEOUT",assertThrows(ExtractionFailure.class,()->new OpenAiExtractor(p,json).extract(input())).code());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<4000);
    }
    @Test void boundsResponseSize(){
        response="x".repeat(300000);
        assertThrows(ExtractionFailure.class,this::extract);
    }
    @Test void missingConfigurationNeverMakesARequest(){
        var p=properties();p.setModel("");
        assertFalse(new OpenAiExtractor(p,json).configured());
        assertEquals("MODEL_NOT_CONFIGURED",assertThrows(ExtractionFailure.class,()->new OpenAiExtractor(p,json).extract(input())).code());
        assertEquals(0,calls.get());
    }
    @Test void rejectsCredentialBearingOrInsecureRemoteUrls(){
        for(String url:List.of("http://api.deepseek.com","https://user:password@example.com","https://example.com?key=secret")){
            var p=properties();p.setBaseUrl(url);
            assertThrows(IllegalArgumentException.class,()->new OpenAiExtractor(p,json));
        }
    }
}
