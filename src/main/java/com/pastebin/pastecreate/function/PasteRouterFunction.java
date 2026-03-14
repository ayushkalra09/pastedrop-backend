package com.pastebin.pastecreate.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.OcrRequest;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import com.pastebin.pastecreate.service.PasteStorageService;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

@Component
public class PasteRouterFunction {

    @Autowired
    private PasteStorageService pasteStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*"
    );

    @Bean
    public Function<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> pasteRouter() {
        return request -> {
            System.out.println("========== LAMBDA REQUEST START ==========");
            try {
                String method = request.getRequestContext().getHttp().getMethod();
                String path = normalizePath(request.getRawPath());
                String body = request.getBody();

                if ("POST".equalsIgnoreCase(method) && path.equals("/paste")) {
                    return createPaste(body);
                }
                if ("GET".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    return getPaste(path, request);
                }
                if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    return deletePaste(path);
                }
                if ("POST".equalsIgnoreCase(method) && path.equals("/ocr")) {
                    return processOcr(body);
                }

                return buildResponse(400, "{\"message\":\"Unsupported route\"}");

            } catch (PasteException e) {
                return handlePasteException(e);
            } catch (Exception e) {
                System.out.println("========== SYSTEM EXCEPTION ==========");
                e.printStackTrace();
                return buildResponse(500, "{\"error\":\"Internal server error\"}");
            } finally {
                System.out.println("========== LAMBDA REQUEST END ==========");
            }
        };
    }

    private APIGatewayV2HTTPResponse createPaste(String body) throws Exception {
        PasteRequest pasteRequest = objectMapper.readValue(body, PasteRequest.class);
        PasteResponse result = pasteStorageService.createPaste(pasteRequest);
        System.out.println("Paste created with keyID = " + result.getKeyID());
        return buildResponse(201, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse getPaste(String path, APIGatewayV2HTTPEvent request) throws Exception {
        String keyID = extractKeyID(path);
        String password = request.getQueryStringParameters() != null
                ? request.getQueryStringParameters().get("password")
                : null;
        System.out.println("Fetching pasteID = " + keyID);

        PasteResponse result = pasteStorageService.getPaste(keyID, password);
        if (result == null) {
            return buildResponse(404, "{\"message\":\"Paste not found\"}");
        }
        return buildResponse(200, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse deletePaste(String path) {
        String keyID = extractKeyID(path);
        System.out.println("Deleting pasteID = " + keyID);
        pasteStorageService.deletePaste(keyID);
        return buildResponse(204, "");
    }

    private APIGatewayV2HTTPResponse processOcr(String body) throws Exception {
        OcrRequest ocrRequest = objectMapper.readValue(body, OcrRequest.class);
        PasteResponse result = pasteStorageService.processOcr(ocrRequest, ocrRequest.getPassword());
        return buildResponse(200, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse handlePasteException(PasteException e) {
        System.out.println("========== BUSINESS EXCEPTION: " + e.getErrorCode() + " ==========");
        return switch (e.getErrorCode()) {
            case PASSWORD_REQUIRED -> buildResponse(401, "{\"error\":\"Password required\"}");
            case INVALID_PASSWORD  -> buildResponse(403, "{\"error\":\"Invalid password\"}");
            default                -> buildResponse(500, "{\"error\":\"Internal server error\"}");
        };
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(statusCode);
        response.setBody(body);
        response.setHeaders(CORS_HEADERS);
        return response;
    }

    private String extractKeyID(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) throw new PasteException(ErrorCode.NOT_FOUND);
        return parts[2];
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        return path.startsWith("/prod") ? path.replaceFirst("/prod", "") : path;
    }
}