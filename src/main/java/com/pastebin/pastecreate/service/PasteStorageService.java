package com.pastebin.pastecreate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.*;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class PasteStorageService {

    private final String DYNAMO_TABLE = "paste_metadata";
    private final String S3_BUCKET = "paste-card-content";
    private final String QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/555150277731/paste-summary-queue";

    private final DynamoDbClient dynamoDbClient;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final RekognitionClient rekognitionClient;
    private final BCryptPasswordEncoder passwordEncoder;
    private final BedrockService bedrockService;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PasteStorageService() {
        Region region = Region.AP_SOUTH_1;
        this.dynamoDbClient = DynamoDbClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.s3Client = S3Client.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.presigner = S3Presigner.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.rekognitionClient = RekognitionClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.bedrockService = new BedrockService();
        this.sqsClient = SqsClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        log.info("event=SERVICE_INIT service=PasteStorageService region={}", region);
    }

    public PasteResponse createPaste(PasteRequest request, String requestId) throws Exception {
        log.info("event=STORAGE_CREATE_START requestId={}", requestId);

        if (request.getContent() != null && request.getContent().length() > 100000) {
            log.warn("event=REJECT_LARGE_TEXT requestId={} size={}", requestId, request.getContent().length());
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }

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
        item.put("summaryStatus", AttributeValue.builder().s("NONE").build());

        if (request.getTtl() != null) {
            long ttlSeconds = request.getTtl();

            if (ttlSeconds <= 0) {
                log.warn("event=REJECT_INVALID_TTL requestId={} ttl={}", requestId, ttlSeconds);
                throw new PasteException(ErrorCode.INVALID_REQUEST);
            }

            // Cap TTL at 7 days
            long maxAllowedTtl = 7L * 24 * 60 * 60;
            if (ttlSeconds > maxAllowedTtl) {
                log.warn("event=TTL_CAPPED requestId={} originalTtl={} cappedTtl={}", requestId, ttlSeconds, maxAllowedTtl);
                ttlSeconds = maxAllowedTtl;
            }

            long expiryEpoch = Instant.now().getEpochSecond() + ttlSeconds;
            item.put("ttl", AttributeValue.builder().n(String.valueOf(expiryEpoch)).build());
        }

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            item.put("passwordHash", AttributeValue.builder().s(passwordEncoder.encode(request.getPassword())).build());
        }

        try {
            dynamoDbClient.putItem(PutItemRequest.builder().tableName(DYNAMO_TABLE).item(item).build());
            log.info("event=STORAGE_CREATE_SUCCESS requestId={} pasteId={}", requestId, pasteId);
        } catch (Exception e) {
            log.error("event=STORAGE_CREATE_ERROR requestId={} pasteId={}", requestId, pasteId, e);
            deleteFromS3(s3Key);
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }

        PasteResponse response = new PasteResponse();
        response.setKeyID(pasteId);
        return response;
    }

    public PasteResponse getPaste(String keyID, String password, String requestId) throws Exception {
        log.info("event=STORAGE_GET_START requestId={} pasteId={}", requestId, keyID);

        GetItemResponse result = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .build());

        if (!result.hasItem()) {
            log.warn("event=STORAGE_GET_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            return null;
        }

        if (result.item().containsKey("ttl")) {
            long expiryEpoch = Long.parseLong(result.item().get("ttl").n());
            if (Instant.now().getEpochSecond() > expiryEpoch) {
                log.warn("event=STORAGE_GET_EXPIRED requestId={} pasteId={}", requestId, keyID);
                deletePaste(keyID, requestId);
                return null;
            }
        }

        if (result.item().containsKey("passwordHash")) {
            if (password == null || password.isBlank()) {
                log.warn("event=STORAGE_GET_PASSWORD_REQUIRED requestId={} pasteId={}", requestId, keyID);
                throw new PasteException(ErrorCode.PASSWORD_REQUIRED);
            }
            String storedHash = result.item().get("passwordHash").s();
            if (!passwordEncoder.matches(password, storedHash)) {
                log.warn("event=STORAGE_GET_AUTH_FAIL requestId={} pasteId={}", requestId, keyID);
                throw new PasteException(ErrorCode.INVALID_PASSWORD);
            }
        }

        UpdateItemResponse updateResponse = dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("ADD viewCount :inc")
                .expressionAttributeValues(Map.of(":inc", AttributeValue.builder().n("1").build()))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());

        String downloadUrl = generatePresignedUrl(result.item().get("s3ObjectKey").s());
        log.info("event=STORAGE_GET_SUCCESS requestId={} pasteId={}", requestId, keyID);

        PasteResponse response = new PasteResponse();
        response.setKeyID(keyID);
        response.setDownloadUrl(downloadUrl);
        response.setViewCount(Long.parseLong(updateResponse.attributes().get("viewCount").n()));
        return response;
    }

    public void deletePaste(String keyID, String requestId) {
        log.info("event=STORAGE_DELETE_START requestId={} pasteId={}", requestId, keyID);
        try {
            GetItemResponse item = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(DYNAMO_TABLE)
                    .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                    .build());

            if (item.hasItem()) {
                String s3Key = item.item().get("s3ObjectKey").s();
                deleteFromS3(s3Key);
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                        .build());
                log.info("event=STORAGE_DELETE_SUCCESS requestId={} pasteId={}", requestId, keyID);
            } else {
                log.warn("event=STORAGE_DELETE_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            }
        } catch (Exception e) {
            log.error("event=STORAGE_DELETE_ERROR requestId={} pasteId={}", requestId, keyID, e);
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }
    }

    public PasteResponse processOcr(OcrRequest request, String password, String requestId) throws Exception {
        log.info("event=STORAGE_OCR_START requestId={}", requestId);

        if (request.getBase64Image() == null || request.getBase64Image().isEmpty()) {
            log.warn("event=REJECT_EMPTY_OCR_IMAGE requestId={}", requestId);
            throw new PasteException(ErrorCode.INVALID_REQUEST);
        }

        byte[] imageBytes = Base64.getDecoder().decode(request.getBase64Image());

        if (imageBytes.length > 5 * 1024 * 1024) {
            log.warn("event=REJECT_LARGE_IMAGE requestId={} size={}", requestId, imageBytes.length);
            throw new PasteException(ErrorCode.INTERNAL_ERROR);
        }

        String imageKey = "ocr_" + UUID.randomUUID() + ".jpg";
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(S3_BUCKET)
                        .key(imageKey)
                        .contentType("image/jpeg")
                        .build(),
                RequestBody.fromBytes(imageBytes));

        try {
            String extractedText = extractTextFromImage(imageKey);
            log.info("event=STORAGE_OCR_REKOGNITION_SUCCESS requestId={}", requestId);

            PasteRequest pasteReq = new PasteRequest();
            pasteReq.setContent(extractedText);
            pasteReq.setPassword(password);
            pasteReq.setTtl(request.getTtl());

            return createPaste(pasteReq, requestId);
        } finally {
            deleteFromS3(imageKey);
        }
    }

    public SummarizeResponse summarizePaste(String keyID, String password, String requestId) throws Exception {
        log.info("event=STORAGE_SUMMARIZE_START requestId={} pasteId={}", requestId, keyID);

        GetItemResponse result = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .build());

        if (!result.hasItem()) {
            log.warn("event=STORAGE_SUMMARIZE_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            return null;
        }

        if (result.item().containsKey("ttl")) {
            long expiryEpoch = Long.parseLong(result.item().get("ttl").n());
            if (Instant.now().getEpochSecond() > expiryEpoch) {
                log.warn("event=STORAGE_SUMMARIZE_EXPIRED requestId={} pasteId={}", requestId, keyID);
                deletePaste(keyID, requestId);
                return null;
            }
        }

        if (result.item().containsKey("passwordHash")) {
            if (password == null || password.isBlank()) {
                log.warn("event=STORAGE_SUMMARIZE_PASSWORD_REQUIRED requestId={} pasteId={}", requestId, keyID);
                throw new PasteException(ErrorCode.PASSWORD_REQUIRED);
            }
            String storedHash = result.item().get("passwordHash").s();
            if (!passwordEncoder.matches(password, storedHash)) {
                log.warn("event=STORAGE_SUMMARIZE_AUTH_FAIL requestId={} pasteId={}", requestId, keyID);
                throw new PasteException(ErrorCode.INVALID_PASSWORD);
            }
        }

        if (result.item().containsKey("summary")) {
            log.info("event=STORAGE_SUMMARIZE_CACHE_HIT requestId={} pasteId={}", requestId, keyID);
            return new SummarizeResponse(keyID, result.item().get("summary").s());
        }

        String currentStatus = result.item()
                .getOrDefault("summaryStatus", AttributeValue.builder().s("NONE").build()).s();
        if ("PENDING".equals(currentStatus)) {
            log.info("event=STORAGE_SUMMARIZE_ALREADY_PENDING requestId={} pasteId={}", requestId, keyID);
            return new SummarizeResponse(keyID, "PENDING");
        }

        updateStatusInDb(keyID, "PENDING");

        try {
            Map<String, String> payload = Map.of("keyID", keyID, "requestId", requestId);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(QUEUE_URL)
                    .messageBody(objectMapper.writeValueAsString(payload))
                    .build());
            log.info("event=STORAGE_SUMMARIZE_ENQUEUED requestId={} pasteId={}", requestId, keyID);
            return new SummarizeResponse(keyID, "PENDING");
        } catch (Exception e) {
            updateStatusInDb(keyID, "ERROR");
            log.error("event=SQS_SEND_FAILURE requestId={} pasteId={}", requestId, keyID, e);
            throw e;
        }
    }

    public void processBackgroundSummary(String keyID, String requestId) throws Exception {
        log.info("event=BACKGROUND_WORKER_START requestId={} pasteId={}", requestId, keyID);
        try {
            GetItemResponse result = dynamoDbClient.getItem(GetItemRequest.builder()
                    .tableName(DYNAMO_TABLE)
                    .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                    .build());

            if (!result.hasItem()) {
                log.warn("event=BACKGROUND_WORKER_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
                return;
            }

            String content = fetchContentFromS3(result.item().get("s3ObjectKey").s());
            String summary = bedrockService.summarize(content, requestId);

            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DYNAMO_TABLE)
                    .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                    .updateExpression("SET summary = :s, summaryStatus = :st")
                    .expressionAttributeValues(Map.of(
                            ":s", AttributeValue.builder().s(summary).build(),
                            ":st", AttributeValue.builder().s("COMPLETED").build()
                    )).build());

            log.info("event=BACKGROUND_WORKER_SUCCESS requestId={} pasteId={}", requestId, keyID);
        } catch (Exception e) {
            log.error("event=BACKGROUND_WORKER_FAILURE requestId={} pasteId={}", requestId, keyID, e);
            updateStatusInDb(keyID, "ERROR");
            throw e;
        }
    }

    private void uploadContentToS3(String key, String content) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(S3_BUCKET).key(key).build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String generatePresignedUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(GetObjectRequest.builder().bucket(S3_BUCKET).key(key).build())
                .build();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        return presignedRequest.url().toString();
    }

    private void deleteFromS3(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(S3_BUCKET).key(key).build());
    }

    private boolean keyExists(String keyID) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .build()).hasItem();
    }

    private String extractTextFromImage(String s3Key) {
        DetectTextRequest request = DetectTextRequest.builder()
                .image(Image.builder()
                        .s3Object(software.amazon.awssdk.services.rekognition.model.S3Object.builder()
                                .bucket(S3_BUCKET).name(s3Key).build())
                        .build())
                .build();
        DetectTextResponse response = rekognitionClient.detectText(request);
        StringBuilder sb = new StringBuilder();
        for (TextDetection text : response.textDetections()) {
            if (text.type() == TextTypes.LINE) {
                sb.append(text.detectedText()).append("\n");
            }
        }
        return sb.toString();
    }

    private String fetchContentFromS3(String key) {
        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(S3_BUCKET).key(key).build()
        );
        return objectBytes.asUtf8String();
    }

    private void updateStatusInDb(String keyID, String status) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("SET summaryStatus = :s")
                .expressionAttributeValues(Map.of(":s", AttributeValue.builder().s(status).build()))
                .build());
    }
}