package com.pastebin.pastecreate.model;

import lombok.Data;

@Data
public class OcrRequest {
    private String base64Image;
    private Long ttl;
}
