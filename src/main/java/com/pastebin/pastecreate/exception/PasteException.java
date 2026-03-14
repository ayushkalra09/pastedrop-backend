package com.pastebin.pastecreate.exception;

import com.pastebin.pastecreate.enums.ErrorCode;

public class PasteException extends RuntimeException {

    private final ErrorCode errorCode;

    public PasteException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}