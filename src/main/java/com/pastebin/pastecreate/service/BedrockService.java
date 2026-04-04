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
        log.info("BedrockService initialized with Model ID: {} in region: {}", MODEL_ID, region);
    }

    public String summarize(String content) throws Exception {
        log.debug("Received summarization request. Content length: {} characters",
                content != null ? content.length() : 0);

        if (content == null || content.isBlank()) {
            log.warn("Empty content provided for summarization.");
            return "Nothing to summarize.";
        }

        // Truncation logic with logging
        String truncatedContent;
        if (content.length() > MAX_INPUT_CHARS) {
            log.info("Content length ({}) exceeds limit. Truncating to {} chars.",
                    content.length(), MAX_INPUT_CHARS);
            truncatedContent = content.substring(0, MAX_INPUT_CHARS) + "...";
        } else {
            truncatedContent = content;
        }

        String prompt = "Summarize the following text in 2-3 sentences:\n\n" + truncatedContent;

        Map<String, Object> requestBody = Map.of(
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "inferenceConfig", Map.of(
                        "maxTokens", MAX_TOKENS,
                        "temperature", 0.5,
                        "topP", 0.9
                )
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
            log.info("Invoking Bedrock model: {}", MODEL_ID);
            InvokeModelResponse invokeResponse = bedrockClient.invokeModel(invokeRequest);
            long duration = System.currentTimeMillis() - startTime;

            String responseJson = invokeResponse.body().asUtf8String();
            log.debug("Bedrock raw response received in {}ms: {}", duration, responseJson);

            // Parsing with error handling/logging
            Map<?, ?> parsed = objectMapper.readValue(responseJson, Map.class);
            Map<?, ?> output = (Map<?, ?>) parsed.get("output");
            Map<?, ?> message = (Map<?, ?>) output.get("message");
            List<?> contentList = (List<?>) message.get("content");

            if (contentList == null || contentList.isEmpty()) {
                log.error("Bedrock returned an empty content list. Response: {}", responseJson);
                return "Error: AI generated an empty response.";
            }

            Map<?, ?> first = (Map<?, ?>) contentList.get(0);
            String summary = first.get("text").toString().trim();

            log.info("Successfully generated summary. Length: {} chars. Duration: {}ms",
                    summary.length(), duration);

            return summary;

        } catch (Exception e) {
            log.error("Failed to invoke Bedrock model {}. Error: {}", MODEL_ID, e.getMessage(), e);
            throw e;
        }
    }
}