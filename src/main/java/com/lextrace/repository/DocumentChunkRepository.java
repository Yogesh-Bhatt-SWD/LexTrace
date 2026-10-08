package com.lextrace.repository;

import com.lextrace.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Query(value = """
        SELECT * FROM document_chunk
        WHERE document_id = :documentId
        ORDER BY chunk_index ASC
        """, nativeQuery = true)
    List<DocumentChunk> findChunksByDocumentId(@Param("documentId") Long documentId);
}
