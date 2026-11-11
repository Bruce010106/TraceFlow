package dev.traceflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.core.io.ClassPathResource;

/** OpenAI-compatible Chat Completions adapter. One bounded request per attempt. */
public class OpenAiExtractor implements TaskExtractor {
    private final AiProperties config;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String prompt;
    private final JsonNode schema;
    public OpenAiExtractor(AiProperties config,ObjectMapper json) {
        this.config=config;
        this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        URI base=URI.create(config.getBaseUrl().replaceAll("/+$",""));
        boolean local=Set.of("localhost","127.0.0.1","[::1]","::1").contains(Objects.toString(base.getHost(),""));
        if((!"https".equals(base.getScheme())&&!("http".equals(base.getScheme())&&local))
            ||base.getHost()==null||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null)
            throw new IllegalArgumentException("AI base URL must be HTTPS, or HTTP on loopback, without credentials or query parameters");
        endpoint=URI.create(base+"/chat/completions");
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        try(var promptFile=new ClassPathResource("prompts/task-extraction-v1.txt").getInputStream();
            var schemaFile=new ClassPathResource("ai/proposal-schema.json").getInputStream()) {
            prompt=new String(promptFile.readAllBytes(),StandardCharsets.UTF_8);
            schema=this.json.readTree(schemaFile);
        } catch(IOException e){throw new IllegalStateException("Extraction prompt resources are unavailable",e);}
    }
    public String mode(){return "llm";}
    public String provider(){return "api.deepseek.com".equals(endpoint.getHost())?"deepseek":"openai-compatible";}
    public String model(){return config.getModel().isBlank()?"unconfigured":config.getModel();}
    public String promptVersion(){return "task-extraction-v1";}
    public boolean configured(){return !config.getModel().isBlank()&&(!config.getApiKey().isBlank()||isLocal());}
    private boolean isLocal(){return Set.of("localhost","127.0.0.1","[::1]","::1").contains(endpoint.getHost());}

    public Result extract(Input input) {
        if(!configured())throw new ExtractionFailure("MODEL_NOT_CONFIGURED");
        long started=System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            var lines=new ArrayList<Map<String,Object>>();
            String[] content=input.content().split("\\n",-1);
            for(int i=0;i<content.length;i++)lines.add(Map.of("line",i+1,"text",content[i]));
            var context=Map.of("referenceDate",input.referenceDate().toString(),"timezone",input.timezone(),
                "documentLines",lines,"existingTasks",input.tasks());
            var body=new LinkedHashMap<String,Object>();
            body.put("model",config.getModel());
            body.put("messages",List.of(Map.of("role","system","content",prompt+"\n输出必须符合以下 JSON schema：\n"+schema),
                Map.of("role","user","content",json.writeValueAsString(context))));
            body.put("response_format","json_schema".equals(config.getResponseFormat())
                ? Map.of("type","json_schema","json_schema",Map.of("name","traceflow_proposals","strict",true,"schema",schema))
                : Map.of("type","json_object"));
            body.put(config.getTokenLimitField(),config.getMaxTokens());
            if(!"provider_default".equals(config.getThinkingMode()))body.put("thinking",Map.of("type",config.getThinkingMode()));
            var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type","application/json").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));
            if(!config.getApiKey().isBlank())builder.header("Authorization","Bearer "+config.getApiKey());
            future=http.sendAsync(builder.build(),ignored->new LimitedBody(262144));
            var response=future.get(config.getTimeoutSeconds(),TimeUnit.SECONDS);
            if(response.statusCode()!=200) {
                String code=switch(response.statusCode()) {
                    case 401,403 -> "MODEL_AUTH_ERROR";
                    case 429 -> "MODEL_RATE_LIMITED";
                    default -> response.statusCode()>=500?"MODEL_UNAVAILABLE":"MODEL_REQUEST_REJECTED";
                };
                throw new ExtractionFailure(code);
            }
            JsonNode envelope=json.readTree(response.body());
            JsonNode choices=envelope.path("choices");
            if(!choices.isArray()||choices.size()!=1)throw new ExtractionFailure("MODEL_INVALID_OUTPUT");
            JsonNode choice=choices.get(0), message=choice.path("message");
            if(!message.path("refusal").isMissingNode()&&!message.path("refusal").isNull()
                &&!message.path("refusal").asText().isBlank())throw new ExtractionFailure("MODEL_REFUSED");
            if(!"stop".equals(choice.path("finish_reason").asText()))throw new ExtractionFailure("MODEL_INCOMPLETE");
            JsonNode contentNode=message.path("content");
            if(!contentNode.isTextual()||contentNode.asText().isBlank())throw new ExtractionFailure("MODEL_INVALID_OUTPUT");
            JsonNode output=json.readTree(contentNode.asText());
            return new Result(output,safeText(envelope.path("model"),80),safeText(envelope.path("id"),200),
                token(envelope.path("usage").path("prompt_tokens")),token(envelope.path("usage").path("completion_tokens")),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        } catch(ExtractionFailure e){throw e;}
        catch(TimeoutException e){throw new ExtractionFailure("MODEL_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExtractionFailure("MODEL_INTERRUPTED");}
        catch(ExecutionException e){
            if(e.getCause() instanceof HttpTimeoutException)throw new ExtractionFailure("MODEL_TIMEOUT");
            throw new ExtractionFailure("MODEL_CONNECTION_ERROR");
        } catch(com.fasterxml.jackson.core.JacksonException e){throw new ExtractionFailure("MODEL_INVALID_OUTPUT");}
        catch(Exception e){throw new ExtractionFailure("MODEL_CONNECTION_ERROR");}
        finally {if(future!=null&&!future.isDone())future.cancel(true);}
    }
    private static String safeText(JsonNode value,int limit){return value.isTextual()?value.asText().substring(0,Math.min(limit,value.asText().length())):null;}
    private static Integer token(JsonNode value){return value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()>=0?value.intValue():null;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final long limit; private long received; private Flow.Subscription subscription; private boolean failed;
        LimitedBody(long limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;delegate.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> buffers){
            if(failed)return;
            for(var buffer:buffers)received+=buffer.remaining();
            if(received>limit){failed=true;subscription.cancel();delegate.onError(new IOException("Response too large"));return;}
            delegate.onNext(buffers);
        }
        public void onError(Throwable error){delegate.onError(error);}
        public void onComplete(){if(!failed)delegate.onComplete();}
    }
}
