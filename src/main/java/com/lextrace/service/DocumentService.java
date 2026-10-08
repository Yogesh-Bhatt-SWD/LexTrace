package com.lextrace.service;

import com.lextrace.dto.DocumentChunkDto;
import com.lextrace.dto.DocumentSummaryDto;
import com.lextrace.entity.Document;
import com.lextrace.entity.DocumentChunk;
import com.lextrace.entity.DocumentStatus;
import com.lextrace.repository.DocumentChunkRepository;
import com.lextrace.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentService {

    private static final int EMBEDDING_DIMENSION = 1024;
    private static final int CHUNK_SIZE_CHARS = 500;
    private static final int CHUNK_OVERLAP_CHARS = 100;
    private static final String UPLOAD_DIR = "uploads/documents";

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.bedrock.model-id:cohere.embed-english-v3}")
    private String bedrockModelId;

    private BedrockRuntimeClient bedrockClient;

    private BedrockRuntimeClient getBedrockClient() {
        if (bedrockClient == null) {
            AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
            bedrockClient = BedrockRuntimeClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(credentialsProvider)
                    .build();
        }
        return bedrockClient;
    }

    /**
     * Creates or retrieves the cached Bedrock Runtime client.
     * Uses DefaultCredentialsProvider to load credentials from
     * environment variables, IAM roles, or profile files.
     */

    public Optional<Document> getDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    public Optional<List<DocumentChunk>> getDocumentChunks(Long documentId) {
        return documentRepository.findById(documentId)
                .map(doc -> chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId));
    }

    public Optional<DocumentSummaryDto> getDocumentSummary(Long documentId) {
        return documentRepository.findById(documentId)
                .map(doc -> {
                    List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
                    return DocumentSummaryDto.builder()
                            .id(doc.getId())
                            .filename(doc.getFilename())
                            .filePath(doc.getFilePath())
                            .fileSize(doc.getFileSize())
                            .pageCount(doc.getPageCount())
                            .chunkCount(doc.getChunkCount())
                            .status(doc.getStatus().name())
                            .uploadedAt(doc.getUploadedAt())
                            .chunks(chunks.stream()
                                    .map(chunk -> DocumentChunkDto.builder()
                                            .id(chunk.getId())
                                            .documentId(chunk.getDocument().getId())
                                            .chunkIndex(chunk.getChunkIndex())
                                            .textLength(chunk.getText() != null ? chunk.getText().length() : 0)
                                            .embeddingDimension(chunk.getEmbedding() != null ? chunk.getEmbedding().length : 0)
                                            .build())
                                    .collect(Collectors.toList()))
                            .build();
                });
    }

    public Document uploadAndProcess(MultipartFile file) throws IOException {
        validatePdf(file);

        Document document = saveDocument(file);
        document.setStatus(DocumentStatus.PENDING);
        document = documentRepository.save(document);

        try {
            processDocument(document);
            document.setStatus(DocumentStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Failed to process document {}: {}", document.getId(), e.getMessage(), e);
            document.setStatus(DocumentStatus.FAILED);
            document = documentRepository.save(document);
            throw new RuntimeException("Document processing failed: " + e.getMessage(), e);
        }

        return documentRepository.save(document);
    }

    private void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();
        boolean isPdfByContentType = contentType != null && contentType.toLowerCase().contains("pdf");
        boolean isPdfByExtension = filename != null && filename.toLowerCase().endsWith(".pdf");
        if (!isPdfByContentType && !isPdfByExtension) {
            throw new IllegalArgumentException("File must be a PDF. Content-Type: " + contentType + ", Filename: " + filename);
        }
    }

    private Document saveDocument(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        Files.createDirectories(uploadPath);

        String filename = file.getOriginalFilename();
        if (filename == null) {
            filename = "document.pdf";
        }
        String filePath = uploadPath.resolve(filename).toString();

        File dest = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(file.getBytes());
        }

        Document doc = Document.builder()
                .filename(filename)
                .filePath(filePath)
                .fileSize(file.getSize())
                .pageCount(0)
                .chunkCount(0)
                .status(DocumentStatus.PENDING)
                .uploadedAt(Instant.now())
                .build();

        return documentRepository.save(doc);
    }

    private void processDocument(Document document) throws IOException {
        document.setStatus(DocumentStatus.EXTRACTING);
        documentRepository.save(document);

        String text = extractText(document.getFilePath());
        document.setPageCount(countPages(document.getFilePath()));

        document.setStatus(DocumentStatus.CHUNKING);
        documentRepository.save(document);

        // Chunk extracted text into pieces
        List<String> chunks = chunkText(text);
        document.setChunkCount(chunks.size());

        document.setStatus(DocumentStatus.EMBEDDING);
        documentRepository.save(document);

        generateAndSaveEmbeddings(document, chunks);
    }

    private String extractText(String filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private int countPages(String filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(new File(filePath))) {
            return doc.getNumberOfPages();
        }
    }

    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;
        int lastEnd = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, length);
            if (end == lastEnd || end <= start || lastEnd == end) {
                break;
            }
            if (end < length) {
                int spaceIdx = text.lastIndexOf(' ', end);
                if (spaceIdx > start + CHUNK_SIZE_CHARS / 2) {
                    end = spaceIdx + 1;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            lastEnd = end;
            start = end - CHUNK_OVERLAP_CHARS;
            if (start < 0) {
                start = 0;
            }
            if (start >= length || start >= lastEnd) {
                break;
            }
        }
        return chunks;
    }

    private void generateAndSaveEmbeddings(Document document, List<String> chunks) {
        if (chunks.isEmpty()) {
            documentRepository.save(document);
            return;
        }

        /**
         * Generates embeddings for all text chunks using AWS Bedrock
         * and saves them to the database. Processes chunks in batches
         * of varying sizes (1, 3, 5) to optimize API usage.
         */
        try (BedrockRuntimeClient client = getBedrockClient()) {
            int totalChunks = chunks.size();
            int[] batchSizes = {1, 3, 5};
            int[] sizesCounter = {0, 0, 0};
            int batchSizeIdx = 0;
            int offset = 0;
            while (offset < totalChunks) {
                int remaining = totalChunks - offset;
                int currentBatchSize = batchSizes[batchSizeIdx];
                int actualSize = Math.min(currentBatchSize, remaining);
                List<String> batch = chunks.subList(offset, offset + actualSize);
                List<float[]> embeddings = generateEmbeddingsBatch(client, batch);
                sizesCounter[batchSizeIdx]++;
                for (int j = 0; j < batch.size(); j++) {
                    DocumentChunk chunk = DocumentChunk.builder()
                            .document(document)
                            .chunkIndex(offset + j)
                            .text(batch.get(j))
                            .embedding(embeddings.get(j))
                            .build();
                    document.addChunk(chunk);
                    chunkRepository.save(chunk);
                }
                offset += actualSize;
                batchSizeIdx = (batchSizeIdx + 1) % batchSizes.length;
            }
            log.info("Embedding batch distribution - size 1: {} times, size 3: {} times, size 5: {} times",
                    sizesCounter[0], sizesCounter[1], sizesCounter[2]);
            documentRepository.save(document);
        } catch (Exception e) {
            log.error("Failed to generate embeddings: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }


    /**
     * Calls AWS Bedrock to generate embeddings for a batch of texts
     * using the Cohere embed-english-v3 model.
     */
    private List<float[]> generateEmbeddingsBatch(BedrockRuntimeClient client, List<String> texts) {
        try {
            String inputJson = buildCohereEmbedRequest(texts);
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(bedrockModelId)
                    .body(SdkBytes.fromString(inputJson, java.nio.charset.StandardCharsets.UTF_8))
                    .contentType("application/json")
                    .accept("application/json")
                    .build();

            // Optional: log request for debugging
            // log.debug("Bedrock request body: {}", inputJson);

            InvokeModelResponse response = client.invokeModel(request);
            String responseBody = response.body().asString(java.nio.charset.StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("Bedrock response body: {}", responseBody);
            }
            return parseCohereEmbeddingResponse(responseBody);
        } catch (Exception e) {
            log.error("Failed to generate embeddings via Bedrock: {}", e.getMessage(), e);
            throw new RuntimeException("Bedrock embedding generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the JSON request body for the Cohere Embed API.
     */
    private String buildCohereEmbedRequest(List<String> texts) {
        StringBuilder textsJson = new StringBuilder("[");
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                textsJson.append(",");
            }
            textsJson.append(escapeJson(texts.get(i)));
        }
        textsJson.append("]");
        return "{"
                + "\"texts\": " + textsJson + ","
                + "\"input_type\": \"search_document\","
                + "\"embedding_types\": [\"float\"]"
                + "}";
    }

    /**
     * Escapes a string for inclusion in a JSON string value.
     */
    private String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * Parses the Cohere embedding response JSON and extracts
     * the float array embeddings.
     */
    private List<float[]> parseCohereEmbeddingResponse(String json) {
        List<float[]> result = new ArrayList<>();
        int embeddingsIdx = json.indexOf("\"embeddings\"");
        if (embeddingsIdx < 0) {
            throw new IllegalArgumentException("Unexpected Bedrock response format: " + json);
        }
        String sub = json.substring(embeddingsIdx);
        int arrStart = sub.indexOf("[");
        int arrEnd = findMatchingBracket(sub, arrStart);
        String embeddingsArray = sub.substring(arrStart + 1, arrEnd);
        String[] items = splitJsonArrayItems(embeddingsArray);
        for (String item : items) {
            int floatIdx = item.indexOf("\"float\"");
            if (floatIdx >= 0) {
                int vecStart = item.indexOf("[", floatIdx);
                int vecEnd = findMatchingBracket(item, vecStart);
                String vecStr = item.substring(vecStart + 1, vecEnd);
                result.add(parseFloatArray(vecStr));
            }
        }
        return result;
    }

    /**
     * Splits a JSON array string into individual items,
     * respecting nested brackets and strings.
     */
    private String[] splitJsonArrayItems(String array) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '"' && (i == 0 || array.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{' || c == '[') {
                    depth++;
                }
                if (c == '}' || c == ']') {
                    depth--;
                }
            }
            if (c == ',' && depth == 0 && !inString) {
                items.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            items.add(current.toString().trim());
        }
        return items.toArray(new String[0]);
    }

    /**
     * Finds the index of the matching closing bracket for the
     * opening bracket at the given start index.
     */
    private int findMatchingBracket(String s, int start) {
        char open = s.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 1;
        boolean inString = false;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == open) {
                    depth++;
                }
                if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return s.length() - 1;
    }

    /**
     * Parses a comma-separated float array string into a float array.
     * Handles optional surrounding brackets.
     */
    private float[] parseFloatArray(String s) {
        s = s.trim();
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] parts = s.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
