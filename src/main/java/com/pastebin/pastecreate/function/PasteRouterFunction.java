package com.pastebin.pastecreate.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.OcrRequest;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import com.pastebin.pastecreate.model.SummarizeResponse;
import com.pastebin.pastecreate.service.PasteStorageService;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

@Component
public class PasteRouterFunction {

    private static final Logger log = LoggerFactory.getLogger(PasteRouterFunction.class);

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

            String requestId = request.getRequestContext().getRequestId();
            String method = request.getRequestContext().getHttp().getMethod();
            String path = normalizePath(request.getRawPath());

            log.info("event=REQUEST_START requestId={} method={} path={}", requestId, method, path);

            try {
                String body = request.getBody();

                if ("POST".equalsIgnoreCase(method) && path.equals("/paste")) {
                    return createPaste(body, requestId);
                }

                if ("GET".equalsIgnoreCase(method) && path.matches("/paste/[^/]+/summarize")) {
                    return summarizePaste(path, request, requestId);
                }

                if ("GET".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    return getPaste(path, request, requestId);
                }

                if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    return deletePaste(path, requestId);
                }

                if ("POST".equalsIgnoreCase(method) && path.equals("/ocr")) {
                    return processOcr(body, requestId);
                }

                log.warn("event=UNSUPPORTED_ROUTE requestId={} method={} path={}", requestId, method, path);
                return buildResponse(400, "{\"message\":\"Unsupported route\"}");

            } catch (PasteException e) {
                log.warn("event=BUSINESS_EXCEPTION requestId={} code={}", requestId, e.getErrorCode());
                return handlePasteException(e);
            } catch (Exception e) {
                log.error("event=SYSTEM_EXCEPTION requestId={} message={}", requestId, e.getMessage(), e);
                return buildResponse(500, "{\"error\":\"Internal server error\"}");
            } finally {
                log.info("event=REQUEST_END requestId={}", requestId);
            }
        };
    }

    private APIGatewayV2HTTPResponse createPaste(String body, String requestId) throws Exception {
        PasteRequest pasteRequest = objectMapper.readValue(body, PasteRequest.class);

        PasteResponse result = pasteStorageService.createPaste(pasteRequest);

        log.info("event=CREATE_PASTE requestId={} pasteId={}", requestId, result.getKeyID());

        return buildResponse(201, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse getPaste(String path, APIGatewayV2HTTPEvent request, String requestId) throws Exception {

        String keyID = extractKeyID(path);

        String password = request.getQueryStringParameters() != null
                ? request.getQueryStringParameters().get("password")
                : null;

        log.info("event=GET_PASTE_START requestId={} pasteId={}", requestId, keyID);

        PasteResponse result = pasteStorageService.getPaste(keyID, password);

        if (result == null) {
            log.warn("event=GET_PASTE_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            return buildResponse(404, "{\"message\":\"Paste not found\"}");
        }

        log.info("event=GET_PASTE_SUCCESS requestId={} pasteId={} views={}",
                requestId, keyID, result.getViewCount());

        return buildResponse(200, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse deletePaste(String path, String requestId) {

        String keyID = extractKeyID(path);

        log.info("event=DELETE_PASTE_START requestId={} pasteId={}", requestId, keyID);

        pasteStorageService.deletePaste(keyID);

        log.info("event=DELETE_PASTE_SUCCESS requestId={} pasteId={}", requestId, keyID);

        return buildResponse(204, "");
    }

    private APIGatewayV2HTTPResponse processOcr(String body, String requestId) throws Exception {

        OcrRequest ocrRequest = objectMapper.readValue(body, OcrRequest.class);

        log.info("event=OCR_START requestId={}", requestId);

        PasteResponse result = pasteStorageService.processOcr(ocrRequest, ocrRequest.getPassword());

        log.info("event=OCR_SUCCESS requestId={} pasteId={}", requestId, result.getKeyID());

        return buildResponse(200, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse summarizePaste(String path, APIGatewayV2HTTPEvent request, String requestId) throws Exception {

        String[] parts = path.split("/");
        if (parts.length < 4) throw new PasteException(ErrorCode.NOT_FOUND);

        String keyID = parts[2];

        String password = request.getQueryStringParameters() != null
                ? request.getQueryStringParameters().get("password")
                : null;

        log.info("event=SUMMARIZE_START requestId={} pasteId={}", requestId, keyID);

        SummarizeResponse result = pasteStorageService.summarizePaste(keyID, password);

        if (result == null) {
            log.warn("event=SUMMARIZE_NOT_FOUND requestId={} pasteId={}", requestId, keyID);
            return buildResponse(404, "{\"message\":\"Paste not found\"}");
        }

        log.info("event=SUMMARIZE_SUCCESS requestId={} pasteId={}", requestId, keyID);

        return buildResponse(200, objectMapper.writeValueAsString(result));
    }

    private APIGatewayV2HTTPResponse handlePasteException(PasteException e) {

        log.warn("event=BUSINESS_EXCEPTION code={}", e.getErrorCode());

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