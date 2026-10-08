package com.lextrace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentDto {
    private Long id;
    private String filename;
    private String filePath;
    private Long fileSize;
    private Integer pageCount;
    private Integer chunkCount;
    private String status;
    private Instant uploadedAt;
}
