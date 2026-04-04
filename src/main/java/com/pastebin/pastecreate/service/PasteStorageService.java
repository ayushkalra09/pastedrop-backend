package com.pastebin.pastecreate.service;

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
        this.dynamoDbClient = DynamoDbClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.s3Client = S3Client.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.presigner = S3Presigner.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.rekognitionClient = RekognitionClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).build();
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.bedrockService = new BedrockService();
        log.info("event=SERVICE_INIT service=PasteStorageService region={}", region);
    }

    public PasteResponse createPaste(PasteRequest request, String requestId) throws Exception {
        log.info("event=STORAGE_CREATE_START requestId={}", requestId);

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
            long expiryEpoch = Instant.now().getEpochSecond() + request.getTtl();
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
                .tableName(DYNAMO_TABLE).key(Map.of("keyID", AttributeValue.builder().s(keyID).build())).build());

        if (!result.hasItem()) {
            log.warn("event=STORAGE_GET_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            return null;
        }

        // TTL and Password checks logic...
        if (result.item().containsKey("passwordHash")) {
            String storedHash = result.item().get("passwordHash").s();
            if (password == null || !passwordEncoder.matches(password, storedHash)) {
                log.warn("event=STORAGE_GET_AUTH_FAIL requestId={} pasteId={}", requestId, keyID);
                throw new PasteException(ErrorCode.INVALID_PASSWORD);
            }
        }

        UpdateItemResponse updateResponse = dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(DYNAMO_TABLE).key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("ADD viewCount :inc").expressionAttributeValues(Map.of(":inc", AttributeValue.builder().n("1").build()))
                .returnValues(ReturnValue.UPDATED_NEW).build());

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
                    .tableName(DYNAMO_TABLE).key(Map.of("keyID", AttributeValue.builder().s(keyID).build())).build());

            if (item.hasItem()) {
                String s3Key = item.item().get("s3ObjectKey").s();
                deleteFromS3(s3Key);
                dynamoDbClient.deleteItem(DeleteItemRequest.builder().tableName(DYNAMO_TABLE)
                        .key(Map.of("keyID", AttributeValue.builder().s(keyID).build())).build());
                log.info("event=STORAGE_DELETE_SUCCESS requestId={} pasteId={}", requestId, keyID);
            }
        } catch (Exception e) {
            log.error("event=STORAGE_DELETE_ERROR requestId={} pasteId={}", requestId, keyID, e);
        }
    }

    public PasteResponse processOcr(OcrRequest request, String password, String requestId) throws Exception {
        log.info("event=STORAGE_OCR_START requestId={}", requestId);
        byte[] imageBytes = Base64.getDecoder().decode(request.getBase64Image());
        String imageKey = "ocr_" + requestId + ".jpg";

        s3Client.putObject(PutObjectRequest.builder().bucket(S3_BUCKET).key(imageKey).build(), RequestBody.fromBytes(imageBytes));

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
                .tableName(DYNAMO_TABLE).key(Map.of("keyID", AttributeValue.builder().s(keyID).build())).build());

        if (!result.hasItem()) return null;

        if (result.item().containsKey("summary")) {
            log.info("event=STORAGE_SUMMARIZE_CACHE_HIT requestId={} pasteId={}", requestId, keyID);
            SummarizeResponse res = new SummarizeResponse();
            res.setKeyID(keyID);
            res.setSummary(result.item().get("summary").s());
            return res;
        }

        String content = fetchContentFromS3(result.item().get("s3ObjectKey").s());
        String summary = bedrockService.summarize(content, requestId);

        dynamoDbClient.updateItem(UpdateItemRequest.builder().tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build()))
                .updateExpression("SET summary = :s").expressionAttributeValues(Map.of(":s", AttributeValue.builder().s(summary).build())).build());

        log.info("event=STORAGE_SUMMARIZE_SUCCESS requestId={} pasteId={}", requestId, keyID);
        SummarizeResponse response = new SummarizeResponse();
        response.setKeyID(keyID);
        response.setSummary(summary);
        return response;
    }

    private void uploadContentToS3(String key, String content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(S3_BUCKET).key(key).build(), RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String generatePresignedUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(GetObjectRequest.builder().bucket(S3_BUCKET).key(key).build()).build()).url().toString();
    }

    private void deleteFromS3(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(S3_BUCKET).key(key).build());
    }

    private boolean keyExists(String keyID) {
        return dynamoDbClient.getItem(GetItemRequest.builder().tableName(DYNAMO_TABLE)
                .key(Map.of("keyID", AttributeValue.builder().s(keyID).build())).build()).hasItem();
    }

    private String extractTextFromImage(String s3Key) {
        DetectTextResponse response = rekognitionClient.detectText(DetectTextRequest.builder()
                .image(Image.builder().s3Object(software.amazon.awssdk.services.rekognition.model.S3Object.builder().bucket(S3_BUCKET).name(s3Key).build()).build()).build());
        StringBuilder sb = new StringBuilder();
        response.textDetections().stream().filter(t -> t.type() == TextTypes.LINE).forEach(t -> sb.append(t.detectedText()).append("\n"));
        return sb.toString();
    }

    private String fetchContentFromS3(String key) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(S3_BUCKET).key(key).build()).asUtf8String();
    }
}