package com.pastebin.pastecreate.model;

import lombok.Data;

@Data
public class PasteRequest {
    private String content;
    private Long ttl;
    private String password;
}