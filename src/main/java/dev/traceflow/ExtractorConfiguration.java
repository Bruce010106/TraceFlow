package dev.traceflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractorConfiguration {
    @Bean TaskExtractor taskExtractor(AiProperties properties,ObjectMapper json) {
        return "mock".equals(properties.getMode()) ? new MockExtractor(json) : new OpenAiExtractor(properties,json);
    }
}
