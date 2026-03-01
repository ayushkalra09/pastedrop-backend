package com.pastebin.pastecreate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class PasteStorageService {

    private final String DYNAMO_TABLE = "paste_metadata";
    private final String S3_BUCKET = "paste-card-content";

    private final DynamoDbClient dynamoDbClient;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    }

    public PasteResponse createPaste(PasteRequest request) throws Exception {

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

        if (request.getTtl() != null) {

            long ttlSeconds = request.getTtl();

            if (ttlSeconds <= 0) {
                throw new IllegalArgumentException("TTL must be positive");
            }

            long maxAllowedTtl = 7 * 24 * 60 * 60; // 7 days

            if (ttlSeconds > maxAllowedTtl) {
                ttlSeconds = maxAllowedTtl;
            }

            long expiryEpoch = Instant.now().getEpochSecond() + ttlSeconds;

            item.put("ttl", AttributeValue.builder()
                    .n(String.valueOf(expiryEpoch))
                    .build());
        }

        PutItemRequest putRequest = PutItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .item(item)
                .build();

        dynamoDbClient.putItem(putRequest);

        PasteResponse response = new PasteResponse();
        response.setKeyID(pasteId);

        return response;
    }

    public PasteResponse getPaste(String keyID) throws Exception {

        GetItemRequest getRequest = GetItemRequest.builder()
                .tableName(DYNAMO_TABLE)
                .key(Map.of(
                        "keyID", AttributeValue.builder().s(keyID).build()
                ))
                .build();

        GetItemResponse result = dynamoDbClient.getItem(getRequest);

        if (!result.hasItem()) {
            return null;
        }

        if (result.item().containsKey("ttl")) {

            long expiryEpoch = Long.parseLong(result.item().get("ttl").n());
            long currentEpoch = Instant.now().getEpochSecond();

            if (currentEpoch > expiryEpoch) {

                // Optionally delete it immediately
                deletePaste(keyID);

                return null;
            }
        }

        String s3ObjectKey = result.item().get("s3ObjectKey").s();

        String downloadUrl = generatePresignedUrl(s3ObjectKey);;

        PasteResponse response = new PasteResponse();

        response.setKeyID(keyID);
        response.setDownloadUrl(downloadUrl);

        return response;
    }

    public void deletePaste(String keyID) {

        try {

            GetItemResponse item = dynamoDbClient.getItem(
                    GetItemRequest.builder()
                            .tableName(DYNAMO_TABLE)
                            .key(Map.of(
                                    "keyID", AttributeValue.builder().s(keyID).build()
                            ))
                            .build()
            );

            if (item.hasItem()) {

                String s3Key = item.item().get("s3ObjectKey").s();

                deleteFromS3(s3Key);

                dynamoDbClient.deleteItem(
                        DeleteItemRequest.builder()
                                .tableName(DYNAMO_TABLE)
                                .key(Map.of(
                                        "keyID", AttributeValue.builder().s(keyID).build()
                                ))
                                .build()
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void uploadContentToS3(String key, String content) {

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(S3_BUCKET)
                        .key(key)
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(
                        content.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private String generatePresignedUrl(String key) {

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(S3_BUCKET)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(10)) // expires in 10 min
                        .getObjectRequest(getObjectRequest)
                        .build();

        PresignedGetObjectRequest presignedRequest =
                presigner.presignGetObject(presignRequest);

        return presignedRequest.url().toString();
    }

    private void deleteFromS3(String key) {

        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(S3_BUCKET)
                        .key(key)
                        .build()
        );
    }

    private boolean keyExists(String keyID) {
        GetItemResponse response = dynamoDbClient.getItem(
                GetItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .key(Map.of(
                                "keyID", AttributeValue.builder().s(keyID).build()
                        ))
                        .build()
        );
        return response.hasItem();
    }
}