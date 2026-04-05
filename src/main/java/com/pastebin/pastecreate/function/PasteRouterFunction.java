package com.pastebin.pastecreate.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.*;
import com.pastebin.pastecreate.service.PasteStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class PasteRouterFunction {

    private static final Logger log = LoggerFactory.getLogger(PasteRouterFunction.class);

    @Autowired
    private PasteStorageService pasteStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public Function<Map<String, Object>, Map<String, Object>> pasteRouter() {
        return event -> {
            try {
                if (isSqsEvent(event)) {
                    handleSqsEvent(event);
                    return null;
                }
                return handleApiGatewayEvent(event);
            } catch (Exception e) {
                log.error("event=CRITICAL_ROUTER_FAILURE error={}", e.getMessage(), e);
                return buildResponse(500, "{\"error\":\"Internal server error\"}");
            }
        };
    }

    private boolean isSqsEvent(Map<String, Object> event) {
        return event.containsKey("Records") && event.get("Records") instanceof List;
    }

    @SuppressWarnings("unchecked")
    private void handleSqsEvent(Map<String, Object> event) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) event.get("Records");
        for (Map<String, Object> record : records) {
            try {
                String body = (String) record.get("body");
                Map<String, String> payload = objectMapper.readValue(body, Map.class);
                pasteStorageService.processBackgroundSummary(payload.get("keyID"), payload.get("requestId"));
            } catch (Exception e) {
                log.error("event=SQS_WORKER_ERROR messageId={}", record.get("messageId"), e);
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleApiGatewayEvent(Map<String, Object> event) {
        Map<String, Object> requestContext = (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
        Map<String, Object> http = (Map<String, Object>) requestContext.getOrDefault("http", Map.of());

        String requestId = (String) requestContext.get("requestId");
        String method    = (String) http.get("method");
        String rawPath   = (String) event.getOrDefault("rawPath", event.get("path"));
        String path      = normalizePath(rawPath);
        String body      = (String) event.get("body");

        Map<String, String> queryParams = (Map<String, String>) event.get("queryStringParameters");
        String password = (queryParams != null) ? queryParams.get("password") : null;

        log.info("event=API_START requestId={} method={} path={}", requestId, method, path);

        try {
            // POST /paste
            if ("POST".equalsIgnoreCase(method) && "/paste".equals(path)) {
                PasteRequest req = objectMapper.readValue(body, PasteRequest.class);
                PasteResponse res = pasteStorageService.createPaste(req, requestId);
                return buildResponse(201, objectMapper.writeValueAsString(res));
            }

            // GET /paste/{id}/summarize
            if ("GET".equalsIgnoreCase(method) && path.matches("/paste/[^/]+/summarize")) {
                String keyID = path.split("/")[2];
                SummarizeResponse res = pasteStorageService.summarizePaste(keyID, password, requestId);
                if (res == null) return buildResponse(404, "{\"error\":\"Paste not found\"}");
                return buildResponse(200, objectMapper.writeValueAsString(res));
            }

            // GET /paste/{id}
            if ("GET".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                String keyID = extractKeyFromPath(path);
                PasteResponse res = pasteStorageService.getPaste(keyID, password, requestId);
                if (res == null) return buildResponse(404, "{\"error\":\"Paste not found\"}");
                return buildResponse(200, objectMapper.writeValueAsString(res));
            }

            // DELETE /paste/{id}
            if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                String keyID = extractKeyFromPath(path);
                pasteStorageService.deletePaste(keyID, requestId);
                return buildResponse(204, "");
            }

            // POST /ocr
            if ("POST".equalsIgnoreCase(method) && "/ocr".equals(path)) {
                OcrRequest req = objectMapper.readValue(body, OcrRequest.class);
                PasteResponse res = pasteStorageService.processOcr(req, req.getPassword(), requestId);
                return buildResponse(200, objectMapper.writeValueAsString(res));
            }

            log.warn("event=UNSUPPORTED_ROUTE path={}", path);
            return buildResponse(404, "{\"error\":\"Route not found\"}");

        } catch (PasteException e) {
            return handlePasteException(e);
        } catch (Exception e) {
            log.error("event=API_FATAL_ERROR requestId={} error={}", requestId, e.getMessage(), e);
            return buildResponse(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private Map<String, Object> buildResponse(int statusCode, String body) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        response.put("body", body);
        response.put("headers", Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS"
        ));
        response.put("isBase64Encoded", false);
        return response;
    }

    private Map<String, Object> handlePasteException(PasteException e) {
        int code = switch (e.getErrorCode()) {
            case PASSWORD_REQUIRED -> 401;
            case INVALID_PASSWORD  -> 403;
            case NOT_FOUND         -> 404;
            default                -> 500;
        };
        return buildResponse(code, String.format("{\"error\":\"%s\"}", e.getErrorCode()));
    }

    private String extractKeyFromPath(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) throw new PasteException(ErrorCode.NOT_FOUND);
        return parts[2];
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String clean = path.startsWith("/prod") ? path.replaceFirst("/prod", "") : path;
        if (!clean.startsWith("/")) clean = "/" + clean;
        if (clean.length() > 1 && clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }
}