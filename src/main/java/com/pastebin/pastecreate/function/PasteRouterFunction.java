package com.pastebin.pastecreate.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pastebin.pastecreate.model.OcrRequest;
import com.pastebin.pastecreate.model.PasteRequest;
import com.pastebin.pastecreate.model.PasteResponse;
import com.pastebin.pastecreate.service.PasteStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import java.util.Map;
import java.util.function.Function;

@Component
public class PasteRouterFunction {

    @Autowired
    private PasteStorageService pasteStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public Function<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> pasteRouter() {

        return request -> {

            System.out.println("========== LAMBDA REQUEST START ==========");

            try {

                System.out.println("Router invoked");

                String method = request.getRequestContext().getHttp().getMethod();
                String rawPath = request.getRawPath();
                String path = normalizePath(rawPath);

                String body = request.getBody();

                APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

                response.setHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*"
                ));

                // CREATE PASTE
                if ("POST".equalsIgnoreCase(method) && path.equals("/paste")) {

                    PasteRequest pasteRequest = objectMapper.readValue(body, PasteRequest.class);

                    PasteResponse result = pasteStorageService.createPaste(pasteRequest);
                    System.out.println("Paste created with keyID = " + result.getKeyID());

                    response.setStatusCode(200);
                    response.setBody(objectMapper.writeValueAsString(result));
                    return response;
                }

                // GET PASTE
                if ("GET".equalsIgnoreCase(method) && path.startsWith("/paste/")) {

                    String[] parts = path.split("/");
                    if (parts.length < 3) throw new RuntimeException("Invalid path format");

                    String keyID = parts[2];
                    System.out.println("Fetching pasteID = " + keyID);

                    String password = null;

                    if (request.getQueryStringParameters() != null) {
                        password = request.getQueryStringParameters().get("password");
                    }

                    PasteResponse result = pasteStorageService.getPaste(keyID, password);

                    if (result == null) {
                        response.setStatusCode(404);
                        response.setBody("{\"message\":\"Paste not found\"}");
                        return response;
                    }

                    response.setStatusCode(200);
                    response.setBody(objectMapper.writeValueAsString(result));
                    return response;
                }

                // DELETE PASTE
                if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/paste/")) {


                    String[] parts = path.split("/");
                    if (parts.length < 3) throw new RuntimeException("Invalid path format");

                    String keyID = parts[2];
                    System.out.println("Deleting pasteID = " + keyID);

                    pasteStorageService.deletePaste(keyID);

                    response.setStatusCode(204);
                    response.setBody("");
                    return response;
                }

                // OCR IMAGE → CREATE PASTE
                if ("POST".equalsIgnoreCase(method) && path.equals("/ocr")) {

                    OcrRequest ocrRequest = objectMapper.readValue(body, OcrRequest.class);

                    PasteResponse result = pasteStorageService.processOcr(ocrRequest, ocrRequest.getPassword());

                    response.setStatusCode(200);
                    response.setBody(objectMapper.writeValueAsString(result));
                    return response;
                }

                System.out.println("Routing → Unsupported route");
                response.setStatusCode(400);
                response.setBody("{\"message\":\"Unsupported route\"}");
                return response;

            }
            catch (RuntimeException e) {

                System.out.println("========== LAMBDA BUSINESS EXCEPTION ==========");
                e.printStackTrace();

                APIGatewayV2HTTPResponse errorResponse = new APIGatewayV2HTTPResponse();
                errorResponse.setHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*"
                ));

                if ("PASSWORD_REQUIRED".equals(e.getMessage())) {
                    errorResponse.setStatusCode(401);
                    errorResponse.setBody("{\"error\":\"Password required\"}");
                    return errorResponse;
                }

                if ("INVALID_PASSWORD".equals(e.getMessage())) {
                    errorResponse.setStatusCode(403);
                    errorResponse.setBody("{\"error\":\"Invalid password\"}");
                    return errorResponse;
                }

                errorResponse.setStatusCode(500);
                errorResponse.setBody("{\"error\":\"Internal server error\"}");
                return errorResponse;

            } catch (Exception e) {

                System.out.println("========== LAMBDA SYSTEM EXCEPTION ==========");
                e.printStackTrace();

                APIGatewayV2HTTPResponse errorResponse = new APIGatewayV2HTTPResponse();
                errorResponse.setStatusCode(500);
                errorResponse.setBody("{\"error\":\"Internal server error\"}");
                errorResponse.setHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*"
                ));
                return errorResponse;
            }
            finally {
                System.out.println("========== LAMBDA REQUEST END ==========");
            }
        };
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        if (path.startsWith("/prod")) {
            return path.replaceFirst("/prod", "");
        }
        return path;
    }
}