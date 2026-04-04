package com.pastebin.pastecreate.function;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.enums.ErrorCode;
import com.pastebin.pastecreate.exception.PasteException;
import com.pastebin.pastecreate.model.OcrRequest;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import com.pastebin.pastecreate.model.SummarizeResponse;
import com.pastebin.pastecreate.service.PasteStorageService;
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

            log.info("event=ROUTER_START requestId={} method={} path={}", requestId, method, path);

            try {
                String body = request.getBody();

                // 1. Create Paste
                if ("POST".equalsIgnoreCase(method) && path.equals("/paste")) {
                    PasteRequest pasteRequest = objectMapper.readValue(body, PasteRequest.class);
                    PasteResponse result = pasteStorageService.createPaste(pasteRequest, requestId);
                    return buildResponse(201, objectMapper.writeValueAsString(result));
                }

                // 2. Summarize Paste
                if ("GET".equalsIgnoreCase(method) && path.matches("/paste/[^/]+/summarize")) {
                    String keyID = path.split("/")[2];
                    String password = getQueryParam(request, "password");
                    SummarizeResponse result = pasteStorageService.summarizePaste(keyID, password, requestId);

                    if (result == null) return buildResponse(404, "{\"message\":\"Paste not found\"}");
                    return buildResponse(200, objectMapper.writeValueAsString(result));
                }

                // 3. Get Paste
                if ("GET".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    String keyID = extractKeyID(path);
                    String password = getQueryParam(request, "password");
                    PasteResponse result = pasteStorageService.getPaste(keyID, password, requestId);

                    if (result == null) return buildResponse(404, "{\"message\":\"Paste not found\"}");
                    return buildResponse(200, objectMapper.writeValueAsString(result));
                }

                // 4. Delete Paste
                if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/paste/")) {
                    String keyID = extractKeyID(path);
                    pasteStorageService.deletePaste(keyID, requestId);
                    return buildResponse(204, "");
                }

                // 5. OCR Process
                if ("POST".equalsIgnoreCase(method) && path.equals("/ocr")) {
                    OcrRequest ocrRequest = objectMapper.readValue(body, OcrRequest.class);
                    PasteResponse result = pasteStorageService.processOcr(ocrRequest, ocrRequest.getPassword(), requestId);
                    return buildResponse(200, objectMapper.writeValueAsString(result));
                }

                log.warn("event=ROUTER_UNSUPPORTED requestId={} method={} path={}", requestId, method, path);
                return buildResponse(400, "{\"message\":\"Unsupported route\"}");

            } catch (PasteException e) {
                log.warn("event=ROUTER_BUSINESS_EX requestId={} code={}", requestId, e.getErrorCode());
                return handlePasteException(e);
            } catch (Exception e) {
                log.error("event=ROUTER_FATAL_ERROR requestId={} message={}", requestId, e.getMessage(), e);
                return buildResponse(500, "{\"error\":\"Internal server error\"}");
            } finally {
                log.info("event=ROUTER_END requestId={}", requestId);
            }
        };
    }

    private APIGatewayV2HTTPResponse handlePasteException(PasteException e) {
        return switch (e.getErrorCode()) {
            case PASSWORD_REQUIRED -> buildResponse(401, "{\"error\":\"Password required\"}");
            case INVALID_PASSWORD  -> buildResponse(403, "{\"error\":\"Invalid password\"}");
            case NOT_FOUND         -> buildResponse(404, "{\"error\":\"Not found\"}");
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

    private String getQueryParam(APIGatewayV2HTTPEvent request, String key) {
        return (request.getQueryStringParameters() != null)
                ? request.getQueryStringParameters().get(key)
                : null;
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        return path.startsWith("/prod") ? path.replaceFirst("/prod", "") : path;
    }
}