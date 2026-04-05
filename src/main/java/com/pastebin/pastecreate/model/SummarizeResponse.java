package com.pastebin.pastecreate.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SummarizeResponse {
    private String keyID;
    private String summary;
}