package com.pastebin.pastecreate.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PasteResponse {

    @JsonProperty("keyID")
    private String keyID;
    private String downloadUrl;
    private Long viewCount;
}