package com.pastebin.pastecreate.enums;

import lombok.Getter;

@Getter
public enum ErrorCode {

    INVALID_REQUEST(400, "Invalid request"),
    PASSWORD_REQUIRED(401, "Password required"),
    INVALID_PASSWORD(403, "Invalid password"),
    NOT_FOUND(404, "Paste not found"),
    INTERNAL_ERROR(500, "Internal server error");

    private final int httpStatus;
    private final String message;

    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}