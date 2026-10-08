package com.lextrace.entity;

import com.lextrace.type.VectorConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "document_chunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Convert(converter = VectorConverter.class)
    @Column(columnDefinition = "vector(1024)")
    private float[] embedding;
}
