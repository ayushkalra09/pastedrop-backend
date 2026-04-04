package com.pastebin.pastecreate.service;

import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.OcrRequest;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import com.pastebin.pastecreate.model.SummarizeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class PasteStorageService {

    private final String DYNAMO_TABLE = "paste_metadata";
    private final String S3_BUCKET = "paste-card-content";

    private final DynamoDbClient dynamoDbClient;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final RekognitionClient rekognitionClient;
    private final BCryptPasswordEncoder passwordEncoder;
    private final BedrockService bedrockService;

    public PasteStorageService() {
        Region region = Region.AP_SOUTH_1;

        this.dynamoDbClient = DynamoDbClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.rekognitionClient = RekognitionClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        this.passwordEncoder = new BCryptPasswordEncoder();
        this.bedrockService = new BedrockService();

        log.info("PasteStorageService initialized in region {}", region);
    }

    public PasteResponse createPaste(PasteRequest request) throws Exception {
        log.info("Initiating paste creation...");

        String pasteId;
        do {
            pasteId = Base62GeneratorService.generateKey(8);
        } while (keyExists(pasteId));

        String s3Key = pasteId + ".txt";

        uploadContentToS3(s3Key, request.getContent());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("keyID", AttributeValue.builder().s(pasteId).build());
        item.put("s3ObjectKey", AttributeValue.builder().s(s3Key).build());
        item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());
        item.put("viewCount", AttributeValue.builder().n("0").build());

        if (request.getTtl() != null) {
            long ttlSeconds = request.getTtl();
            if (ttlSeconds <= 0) {
                log.warn("Invalid TTL provided: {}", ttlSeconds);
                throw new PasteException(ErrorCode.INVALID_REQUEST);
            }

            long maxAllowedTtl = 7 * 24 * 60 * 60; // 7 days
            if (ttlSeconds > maxAllowedTtl) {
                log.debug("Capping TTL from {} to max allowed {}", ttlSeconds, maxAllowedTtl);
                ttlSeconds = maxAllowedTtl;
            }

            long expiryEpoch = Instant.now().getEpochSecond() + ttlSeconds;
            item.put("ttl", AttributeValue.builder().n(String.valueOf(expiryEpoch)).build());
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            log.debug("Applying password protection to paste {}", pasteId);
            String passwordHash = passwordEncoder.encode(request.getPassword());
            item.put("passwordHash", AttributeValue.builder().s(passwordHash).build());
        }

        PutItemRequest putRequest = PutItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .item(item)
                .build();

        try {
            dynamoDbClient.putItem(putRequest);
            log.info("Successfully created paste with ID: {}", pasteId);
        } catch (Exception e) {
            log.error("Failed to save metadata to DynamoDB for ID: {}. Cleaning up S3.", pasteId, e);
            deleteFromS3(s3Key);
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }

        PasteResponse response = new PasteResponse();
        response.setKeyID(pasteId);
        return response;
    }

    public PasteResponse getPaste(String keyID, String password) throws Exception {
        log.info("Fetching paste for ID: {}", keyID);

        GetItemRequest getRequest = GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .build();

        GetItemResponse result = dynamoDbClient.getItem(getRequest);

        if (!result.hasItem()) {
            log.warn("Paste not found for ID: {}", keyID);
            return null;
        }

        // TTL check
        if (result.item().containsKey("ttl")) {
            long expiryEpoch = Long.parseLong(result.item().get("ttl").n());
            long currentEpoch = Instant.now().getEpochSecond();

            if (currentEpoch > expiryEpoch) {
                log.info("Paste {} has expired. Triggering deletion.", keyID);
                deletePaste(keyID);
                return null;
            }
        }

        // Password check
        if (result.item().containsKey("passwordHash")) {
            if (password == null || password.isBlank()) {
                log.warn("Access denied for paste {}: Password required but not provided.", keyID);
                throw new PasteException(ErrorCode.PASSWORD_REQUIRED);
            }

            String storedHash = result.item().get("passwordHash").s();
            if (!passwordEncoder.matches(password, storedHash)) {
                log.warn("Access denied for paste {}: Invalid password provided.", keyID);
                throw new PasteException(ErrorCode.INVALID_PASSWORD);
            }
        }

        // Increment View Count
        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("ADD viewCount :inc")
                .expressionAttributeValues(Map.of(":inc", AttributeValue.builder().n("1").build()))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build();

        UpdateItemResponse updateResponse = dynamoDbClient.updateItem(updateRequest);
        String updatedViewCount = updateResponse.attributes().get("viewCount").n();

        String s3ObjectKey = result.item().get("s3ObjectKey").s();
        String downloadUrl = generatePresignedUrl(s3ObjectKey);

        log.debug("Generated presigned URL for paste {}. New view count: {}", keyID, updatedViewCount);

        PasteResponse response = new PasteResponse();
        response.setKeyID(keyID);
        response.setDownloadUrl(downloadUrl);
        response.setViewCount(Long.parseLong(updatedViewCount));

        return response;
    }

    public void deletePaste(String keyID) {
        log.info("Attempting to delete paste: {}", keyID);
        try {
            GetItemResponse item = dynamoDbClient.getItem(
                    GetItemRequest.builder()
                            .tableName(DYNAMO_TABLE)
                            .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                            .build()
            );

            if (item.hasItem()) {
                dynamoDbClient.deleteItem(
                        DeleteItemRequest.builder()
                                .tableName(DYNAMO_TABLE)
                                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                                .build()
                );
                String s3Key = item.item().get("s3ObjectKey").s();
                deleteFromS3(s3Key);
                log.info("Successfully deleted paste {} and its S3 object", keyID);
            } else {
                log.warn("Delete skipped: Paste {} not found in DynamoDB", keyID);
            }

        } catch (Exception e) {
            log.error("Error occurred while deleting paste: {}", keyID, e);
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }
    }

    public PasteResponse processOcr(OcrRequest request, String password) throws Exception {
        log.info("Starting OCR processing request...");

        if (request.getBase64Image() == null || request.getBase64Image().isEmpty()) {
            throw new PasteException(ErrorCode.INVALID_REQUEST);
        }

        byte[] imageBytes = Base64.getDecoder().decode(request.getBase64Image());
        String imageKey = "ocr_temp_" + Base62GeneratorService.generateKey(8) + ".jpg";

        log.debug("Uploading temporary OCR image to S3: {}", imageKey);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(S3_BUCKET)
                .key(imageKey)
                .contentType("image/jpeg")
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));

        String extractedText;
        try {
            log.info("Calling AWS Rekognition for text extraction...");
            extractedText = extractTextFromImage(imageKey);
            log.info("OCR extraction complete. Text length: {} chars", extractedText.length());
        } finally {
            deleteFromS3(imageKey);
            log.debug("Temporary OCR image {} deleted", imageKey);
        }

        PasteRequest pasteRequest = new PasteRequest();
        pasteRequest.setContent(extractedText);
        pasteRequest.setTtl(request.getTtl());
        pasteRequest.setPassword(password);

        return createPaste(pasteRequest);
    }

    public SummarizeResponse summarizePaste(String keyID, String password) throws Exception {
        log.info("Requested summary for paste: {}", keyID);

        GetItemRequest getRequest = GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .build();

        GetItemResponse result = dynamoDbClient.getItem(getRequest);

        if (!result.hasItem()) {
            log.warn("Summarization failed: Paste {} not found", keyID);
            return null;
        }

        // TTL check
        if (result.item().containsKey("ttl")) {
            long expiryEpoch = Long.parseLong(result.item().get("ttl").n());
            if (Instant.now().getEpochSecond() > expiryEpoch) {
                log.info("Paste {} expired during summary request", keyID);
                deletePaste(keyID);
                return null;
            }
        }

        // Password check
        if (result.item().containsKey("passwordHash")) {
            if (password == null || password.isBlank()) {
                throw new PasteException(ErrorCode.PASSWORD_REQUIRED);
            }
            String storedHash = result.item().get("passwordHash").s();
            if (!passwordEncoder.matches(password, storedHash)) {
                log.warn("Invalid password for summary request on paste {}", keyID);
                throw new PasteException(ErrorCode.INVALID_PASSWORD);
            }
        }

        if (result.item().containsKey("summary")) {
            log.info("Returning cached summary for paste {}", keyID);
            String cachedSummary = result.item().get("summary").s();

            SummarizeResponse response = new SummarizeResponse();
            response.setKeyID(keyID);
            response.setSummary(cachedSummary);
            return response;
        }

        String s3ObjectKey = result.item().get("s3ObjectKey").s();
        String content = fetchContentFromS3(s3ObjectKey);

        log.info("Calling Bedrock AI for summarization of paste {}", keyID);
        String summary = bedrockService.summarize(content);

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("SET summary = :s, summaryGeneratedAt = :t")
                .expressionAttributeValues(Map.of(
                        ":s", AttributeValue.builder().s(summary).build(),
                        ":t", AttributeValue.builder().s(Instant.now().toString()).build()
                ))
                .build();

        dynamoDbClient.updateItem(updateRequest);
        log.info("Successfully generated and cached summary for paste {}", keyID);

        SummarizeResponse response = new SummarizeResponse();
        response.setKeyID(keyID);
        response.setSummary(summary);

        return response;
    }

    // --- Private Helper Methods ---

    private void uploadContentToS3(String key, String content) {
        log.debug("Uploading content to S3: {}", key);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(S3_BUCKET)
                        .key(key)
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String generatePresignedUrl(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(S3_BUCKET)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    private void deleteFromS3(String key) {
        log.debug("Deleting object from S3: {}", key);
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(S3_BUCKET)
                            .key(key)
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to delete S3 object: {}", key, e);
        }
    }

    private boolean keyExists(String keyID) {
        GetItemResponse response = dynamoDbClient.getItem(
                GetItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                        .build()
        );
        return response.hasItem();
    }

    private String extractTextFromImage(String s3Key) {
        DetectTextRequest request = DetectTextRequest.builder()
                .image(Image.builder()
                        .s3Object(software.amazon.awssdk.services.rekognition.model.S3Object.builder()
                                .bucket(S3_BUCKET)
                                .name(s3Key)
                                .build())
                        .build())
                .build();

        DetectTextResponse response = rekognitionClient.detectText(request);
        StringBuilder extractedText = new StringBuilder();

        for (TextDetection text : response.textDetections()) {
            if (text.type() == TextTypes.LINE) {
                extractedText.append(text.detectedText()).append("\n");
            }
        }
        return extractedText.toString();
    }

    private String fetchContentFromS3(String key) {
        log.debug("Fetching raw content from S3: {}", key);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(S3_BUCKET)
                .key(key)
                .build();

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
        return objectBytes.asUtf8String();
    }
}