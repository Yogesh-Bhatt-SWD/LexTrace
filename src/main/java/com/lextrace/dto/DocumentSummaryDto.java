package com.lextrace.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class DocumentSummaryDto {
    private Long id;
    private String filename;
    private String filePath;
    private Long fileSize;
    private Integer pageCount;
    private Integer chunkCount;
    private String status;
    private Instant uploadedAt;
    private List<DocumentChunkDto> chunks;
}
