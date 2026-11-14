package com.neuedu.ems.controller.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.service.impl.ai.PrivateAssistantClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ai/private")
public class PrivateAssistantController {
    private static final Set<String> ACTIONS = Set.of("status", "documents.list", "documents.download",
            "documents.delete", "documents.preview", "jobs.retry", "jobs.cancel", "preferences.save",
            "question", "graph", "graph.build", "history", "history.clear",
            "notes.list", "notes.add", "notes.update", "notes.delete",
            "scans.discard", "scans.page", "tools.status", "tools.search", "tools.fetch", "tools.tts", "tools.tts.private");
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;
    private final PrivateAssistantClient client;
    private final ObjectMapper mapper;

    public PrivateAssistantController(PrivateAssistantClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @PostMapping("/dispatch")
    public ResultData dispatch(@RequestAttribute("userId") Long uid, @RequestBody Dispatch request) {
        if (request == null || request.action() == null || !ACTIONS.contains(request.action())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的操作");
        }
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, request.action(),
                request.payload() == null ? Map.of() : request.payload()));
    }

    @PostMapping("/documents")
    public ResultData upload(@RequestAttribute("userId") Long uid, @RequestParam("file") MultipartFile file,
                             @RequestParam(value="documentId", required=false) String documentId) throws IOException {
        Map<String, Object> payload = filePayload(file, MAX_DOCUMENT_BYTES, "请选择不超过 8 MB 的文件");
        if (documentId != null) payload.put("documentId", documentId);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "documents.upload", payload));
    }

    @PostMapping("/scans/prepare")
    public ResultData prepareScan(@RequestAttribute("userId") Long uid, @RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "documentId", required = false) String documentId,
                                  @RequestParam(value = "baseVersion", required = false) Integer baseVersion) throws IOException {
        Map<String, Object> payload = filePayload(file, MAX_DOCUMENT_BYTES, "请选择不超过 8 MB 的扫描件");
        if (documentId != null) payload.put("documentId", documentId);
        if (baseVersion != null) payload.put("baseVersion", baseVersion);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "scans.prepare", payload));
    }

    @PostMapping("/scans/recognize")
    public ResultData recognize(@RequestAttribute("userId") Long uid, @RequestParam("file") MultipartFile file,
                                @RequestParam("confirmed") boolean confirmed,
                                @RequestParam("reviewId") String reviewId, @RequestParam("pages") String pages) throws IOException {
        return submitScan(uid, file, reviewId, pages, confirmed, "scans.recognize");
    }

    @PostMapping("/scans/publish")
    public ResultData publish(@RequestAttribute("userId") Long uid, @RequestParam("file") MultipartFile file,
                              @RequestParam("reviewId") String reviewId, @RequestParam("pages") String pages,
                              @RequestParam("confirmed") boolean confirmed) throws IOException {
        return submitScan(uid, file, reviewId, pages, confirmed, "scans.publish");
    }

    private ResultData submitScan(Long uid, MultipartFile file, String reviewId, String pages,
                                  boolean confirmed, String action) throws IOException {
        requireConfirmation(confirmed);
        if (pages.length() > 250_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别文字过多，请拆分后上传");
        }
        var review = parseReview(pages);
        Map<String, Object> payload = filePayload(file, MAX_DOCUMENT_BYTES, "请选择不超过 8 MB 的扫描件");
        payload.remove("name");
        payload.put("confirmed", true);
        payload.put("reviewId", reviewId);
        payload.put("pages", review);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, action, payload));
    }

    @PostMapping("/tools/asr")
    public ResultData transcribe(@RequestAttribute("userId") Long uid, @RequestParam("file") MultipartFile file,
                                 @RequestParam("confirmed") boolean confirmed) throws IOException {
        requireConfirmation(confirmed);
        Map<String, Object> payload = filePayload(file, 4L * 1024 * 1024, "请选择不超过 4 MB 的录音");
        payload.remove("name");
        payload.put("confirmed", true);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "tools.asr", payload));
    }

    private JsonNode parseReview(String pages) {
        try {
            var review = mapper.readTree(pages);
            if (review != null && review.isArray()) return review;
        } catch (IOException ignored) {
            // Use a fixed error rather than returning OCR text from the parse exception.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别预览格式无效");
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先确认本次操作");
    }

    private static Map<String, Object> filePayload(MultipartFile file, long limit, String error) throws IOException {
        if (file.isEmpty() || file.getSize() > limit) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", file.getOriginalFilename() == null ? "upload.txt" : file.getOriginalFilename());
        payload.put("content", Base64.getEncoder().encodeToString(file.getBytes()));
        return payload;
    }

    public record Dispatch(String action, Map<String, Object> payload) {}
}
