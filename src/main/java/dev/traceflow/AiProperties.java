package dev.traceflow;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix="traceflow.ai")
public class AiProperties {
    @Pattern(regexp="llm|mock") private String mode="llm";
    private String baseUrl="https://api.deepseek.com";
    @Size(max=80) private String model="deepseek-flash";
    private String apiKey="";
    @Pattern(regexp="json_schema|json_object") private String responseFormat="json_object";
    @Pattern(regexp="max_tokens|max_completion_tokens") private String tokenLimitField="max_tokens";
    @Pattern(regexp="provider_default|enabled|disabled") private String thinkingMode="disabled";
    @Min(1) @Max(90) private int timeoutSeconds=45;
    @Min(256) @Max(8192) private int maxTokens=4096;
    public String getMode(){return mode;} public void setMode(String value){mode=value;}
    public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String value){baseUrl=value;}
    public String getModel(){return model;} public void setModel(String value){model=value;}
    public String getApiKey(){if(!apiKey.isBlank())return apiKey; String value=System.getenv("TRACEFLOW_AI_API_KEY");if(value==null||value.isBlank())value=System.getenv("DEEPSEEK_API_KEY");return value==null?"":value;} public void setApiKey(String value){apiKey=value;}
    public String getResponseFormat(){return responseFormat;} public void setResponseFormat(String value){responseFormat=value;}
    public String getTokenLimitField(){return tokenLimitField;} public void setTokenLimitField(String value){tokenLimitField=value;}
    public String getThinkingMode(){return thinkingMode;} public void setThinkingMode(String value){thinkingMode=value;}
    public int getTimeoutSeconds(){return timeoutSeconds;} public void setTimeoutSeconds(int value){timeoutSeconds=value;}
    public int getMaxTokens(){return maxTokens;} public void setMaxTokens(int value){maxTokens=value;}
}
