package com.pastebin.pastecreate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;
import java.util.Map;

@Service
public class BedrockService {

    private static final String MODEL_ID = "apac.amazon.nova-micro-v1:0";
    private static final int MAX_TOKENS = 200;

    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;

    public BedrockService() {
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String summarize(String content) throws Exception {

        if (content == null || content.isBlank()) {
            return "Nothing to summarize.";
        }

        String truncatedContent = content.length() > 3000
                ? content.substring(0, 3000) + "..."
                : content;

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

        InvokeModelResponse invokeResponse = bedrockClient.invokeModel(invokeRequest);

        String responseJson = invokeResponse.body().asUtf8String();

        // Nova response structure: output.message.content[0].text
        Map<?, ?> parsed = objectMapper.readValue(responseJson, Map.class);
        Map<?, ?> output = (Map<?, ?>) parsed.get("output");
        Map<?, ?> message = (Map<?, ?>) output.get("message");
        List<?> contentList = (List<?>) message.get("content");
        Map<?, ?> first = (Map<?, ?>) contentList.get(0);

        return first.get("text").toString().trim();
    }
}