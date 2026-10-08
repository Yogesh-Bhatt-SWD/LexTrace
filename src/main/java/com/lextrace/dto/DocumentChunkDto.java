package com.lextrace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunkDto {
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String text;
    private Integer textLength;
    private Integer embeddingDimension;
}
