package com.pastebin.pastecreate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BedrockService {

    private static final String MODEL_ID = "apac.amazon.nova-micro-v1:0";
    private static final int MAX_TOKENS = 200;
    private static final int MAX_INPUT_CHARS = 3000;

    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;

    public BedrockService() {
        Region region = Region.AP_SOUTH_1;
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("event=SERVICE_INIT service=BedrockService region={}", region);
    }

    public String summarize(String content, String requestId) throws Exception {
        if (content == null || content.isBlank()) {
            log.warn("event=BEDROCK_SKIP requestId={} reason=empty_content", requestId);
            return "Nothing to summarize.";
        }

        String truncatedContent = content.length() > MAX_INPUT_CHARS
                ? content.substring(0, MAX_INPUT_CHARS) + "..."
                : content;

        String prompt = "Summarize the following text in 2-3 sentences:\n\n" + truncatedContent;

        Map<String, Object> requestBody = Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(Map.of("text", prompt)))
                ),
                "inferenceConfig", Map.of("maxTokens", MAX_TOKENS, "temperature", 0.5, "topP", 0.9)
        );

        String requestJson = objectMapper.writeValueAsString(requestBody);

        InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                .modelId(MODEL_ID)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(requestJson))
                .build();

        long startTime = System.currentTimeMillis();
        try {
            log.info("event=BEDROCK_INVOKE_START requestId={} model={}", requestId, MODEL_ID);
            InvokeModelResponse invokeResponse = bedrockClient.invokeModel(invokeRequest);
            long duration = System.currentTimeMillis() - startTime;

            String responseJson = invokeResponse.body().asUtf8String();

            Map<?, ?> parsed = objectMapper.readValue(responseJson, Map.class);
            Map<?, ?> output = (Map<?, ?>) parsed.get("output");
            Map<?, ?> message = (Map<?, ?>) output.get("message");
            List<?> contentList = (List<?>) message.get("content");
            Map<?, ?> first = (Map<?, ?>) contentList.get(0);

            String summary = first.get("text").toString().trim();
            log.info("event=BEDROCK_INVOKE_SUCCESS requestId={} duration_ms={} summary_len={}",
                    requestId, duration, summary.length());

            return summary;
        } catch (Exception e) {
            log.error("event=BEDROCK_INVOKE_ERROR requestId={} message={}", requestId, e.getMessage(), e);
            throw e;
        }
    }
}