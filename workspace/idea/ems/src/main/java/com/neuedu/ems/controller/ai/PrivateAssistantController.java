package com.neuedu.ems.controller.ai;

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
            "question", "graph", "graph.build", "history", "history.clear");
    private final PrivateAssistantClient client;
    public PrivateAssistantController(PrivateAssistantClient client) { this.client = client; }

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
        if (file.isEmpty() || file.getSize() > 8L*1024*1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不超过 8 MB 的文件");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", file.getOriginalFilename() == null ? "upload.txt" : file.getOriginalFilename());
        payload.put("content", Base64.getEncoder().encodeToString(file.getBytes()));
        if (documentId != null) payload.put("documentId", documentId);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "documents.upload", payload));
    }

    public record Dispatch(String action, Map<String, Object> payload) {}
}
