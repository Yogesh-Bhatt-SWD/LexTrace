package com.lextrace.controller;

import com.lextrace.dto.DocumentDto;
import com.lextrace.entity.Document;
import com.lextrace.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file provided"));
        }

        try {
            Document document = documentService.uploadAndProcess(file);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(toDto(document));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File processing error: " + e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        return documentService.getDocumentById(id)
                .map(doc -> ResponseEntity.ok(toDto(doc)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/chunks")
    public ResponseEntity<?> getDocumentChunks(@PathVariable Long id) {
        return documentService.getDocumentChunks(id)
                .map(chunks -> ResponseEntity.ok(chunks))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<?> getDocumentSummary(@PathVariable Long id) {
        return documentService.getDocumentSummary(id)
                .map(summary -> ResponseEntity.ok(summary))
                .orElse(ResponseEntity.notFound().build());
    }

    private DocumentDto toDto(Document doc) {
        return DocumentDto.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .filePath(doc.getFilePath())
                .fileSize(doc.getFileSize())
                .pageCount(doc.getPageCount())
                .chunkCount(doc.getChunkCount())
                .status(doc.getStatus().name())
                .uploadedAt(doc.getUploadedAt())
                .build();
    }
}
