package com.neuedu.ems.controller.sys;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.service.impl.ai.PrivateAssistantClient;
import com.neuedu.ems.service.sys.AuthService;
import com.neuedu.ems.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth/face")
public class FaceAuthController {
    private final AuthService auth;
    private final PrivateAssistantClient client;
    private final JwtUtil jwt;
    private final ConcurrentHashMap<String, Long> nextChallenge = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nextFrame = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nextFrameSession = new ConcurrentHashMap<>();
    private java.util.function.LongSupplier frameClock = System::currentTimeMillis;

    public FaceAuthController(AuthService auth, PrivateAssistantClient client, JwtUtil jwt) {
        this.auth = auth; this.client = client; this.jwt = jwt;
    }

    private void throttle(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        if (nextChallenge.size() > 1000) nextChallenge.entrySet().removeIf(e -> e.getValue() < now);
        Long previous = nextChallenge.putIfAbsent(request.getRemoteAddr(), now + 3000);
        if (previous != null && (previous > now || !nextChallenge.replace(request.getRemoteAddr(), previous, now+3000))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再尝试人脸认证");
        }
    }

    private Long candidate(Map<String, Object> payload) {
        String username = String.valueOf(payload.getOrDefault("username", ""));
        if (username.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号无效");
        Long uid = auth.faceCandidate(username);
        if (uid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法完成人脸认证，请使用密码登录");
        return uid;
    }

    private Map<String, Object> framePayload(Map<String, Object> payload, String purpose) {
        if (!(payload.get("step") instanceof Integer step) || step < 0 || step > 2
                || !(payload.get("challengeId") instanceof String challenge) || !challenge.matches("[a-f0-9]{32}")
                || !(payload.get("frame") instanceof String frame) || frame.length() > 1_400_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集画面或步骤无效");
        }
        Map<String,Object> result = new java.util.HashMap<>(Map.of("step", step, "challengeId", challenge,
                "frame", frame, "purpose", purpose));
        if (step > 0) {
            if (!(payload.get("referenceFrame") instanceof String reference) || reference.length() > 1_400_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请重新采集正面画面");
            }
            result.put("referenceFrame", reference);
        }
        return result;
    }

    private void reserveFrame(ConcurrentHashMap<String,Long> limits, String key, long delay) {
        long now = frameClock.getAsLong();
        if (limits.size() > 1000) limits.entrySet().removeIf(e -> e.getValue() < now);
        Long previous = limits.putIfAbsent(key, now + delay);
        if (previous != null && (previous > now || !limits.replace(key, previous, now + delay))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请保持姿态，稍后继续检测");
        }
    }
    private void throttleFrameSession(Long uid, Map<String,Object> frame) {
        reserveFrame(nextFrameSession, uid + ":" + frame.get("purpose") + ":" + frame.get("challengeId"), 700);
    }

    @PostMapping("/identify-challenge")
    public ResultData identificationChallenge(HttpServletRequest request) {
        throttle(request);
        return ResultData.success(ResultCode.SUCCESS, client.identify("face.identify.challenge",
                Map.of("eligibleIds", auth.faceIdentificationCandidates())));
    }

    @PostMapping("/identify-frame")
    public ResultData identificationFrame(HttpServletRequest request, @RequestBody Map<String,Object> payload) {
        Map<String,Object> frame = framePayload(payload, "login");
        reserveFrame(nextFrame, request.getRemoteAddr(), 100);
        throttleFrameSession(0L, frame);
        return ResultData.success(ResultCode.SUCCESS, client.identify("face.identify.frame", frame));
    }

    @PostMapping("/identify-login")
    public ResultData identificationLogin(HttpServletRequest request, @RequestBody Map<String,Object> payload) {
        throttle(request);
        Map<String,Object> trusted = new java.util.HashMap<>();
        trusted.put("eligibleIds", auth.faceIdentificationCandidates());
        trusted.put("challengeId", payload.get("challengeId"));
        trusted.put("frames", payload.get("frames"));
        Object answer = client.identify("face.identify.verify", trusted);
        if (!(answer instanceof Map<?,?> result) || !Boolean.TRUE.equals(result.get("verified"))
                || !(result.get("userId") instanceof Number id)
                || !(id instanceof Long || id instanceof Integer) || id.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未能唯一识别已录入账号，请重试或使用密码登录");
        }
        Long uid = id.longValue();
        if (!((java.util.List<?>)trusted.get("eligibleIds")).contains(uid)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号状态已变化，请使用密码登录");
        }
        SysUser user = auth.completeFaceLogin(uid, true);
        if (user == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号不可用，请使用密码登录");
        ResultData response = ResultData.success(ResultCode.SUCCESS, user);
        response.put("token", jwt.generateToken(user.getUserId(), user.getUsername()));
        return response;
    }

    @PostMapping("/frame")
    public ResultData loginFrame(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        Map<String, Object> frame = framePayload(payload, "login");
        reserveFrame(nextFrame, request.getRemoteAddr(), 100);
        Long uid = candidate(payload);
        throttleFrameSession(uid, frame);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.frame", frame));
    }

    @PostMapping("/frame-enroll")
    public ResultData enrollmentFrame(HttpServletRequest request, @RequestAttribute("userId") Long uid,
                                     @RequestBody Map<String, Object> payload) {
        Map<String,Object> frame = framePayload(payload, "enroll");
        reserveFrame(nextFrame, request.getRemoteAddr(), 100);
        if (auth.getUserInfo(uid) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        throttleFrameSession(uid, frame);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.frame", frame));
    }

    private void requireReauth(Long uid, Map<String, Object> payload) {
        if (!auth.reauthenticate(uid, String.valueOf(payload.getOrDefault("password", "")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码验证未通过");
        }
    }

    @PostMapping("/challenge")
    public ResultData challenge(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        throttle(request);
        Long uid = candidate(payload);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.challenge", Map.of("purpose", "login")));
    }

    @PostMapping("/login")
    public ResultData login(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
        throttle(request);
        Long uid = candidate(payload);
        Object answer;
        // Capture/pose validation errors are not an identity mismatch and must not lock the account.
        answer = client.call(uid, "face.verify", payload);
        boolean verified = answer instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("verified"));
        SysUser user = auth.completeFaceLogin(uid, verified);
        if (user == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人脸验证未通过，请重试或使用密码登录");
        ResultData result = ResultData.success(ResultCode.SUCCESS, user);
        result.put("token", jwt.generateToken(user.getUserId(), user.getUsername()));
        return result;
    }

    @GetMapping("/me")
    public ResultData status(@RequestAttribute("userId") Long uid) {
        if (auth.getUserInfo(uid) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.status", Map.of()));
    }

    @PostMapping("/challenge-enroll")
    public ResultData enrollmentChallenge(@RequestAttribute("userId") Long uid, @RequestBody Map<String, Object> payload) {
        requireReauth(uid, payload);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.challenge", Map.of("purpose", "enroll")));
    }

    @PostMapping("/enroll")
    public ResultData enroll(@RequestAttribute("userId") Long uid, @RequestBody Map<String, Object> payload) {
        requireReauth(uid, payload);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.enroll", payload));
    }

    @PostMapping("/reset")
    public ResultData reset(@RequestAttribute("userId") Long uid, @RequestBody Map<String, Object> payload) {
        requireReauth(uid, payload);
        return ResultData.success(ResultCode.SUCCESS, client.call(uid, "face.reset", Map.of("actor", uid)));
    }

    @DeleteMapping("/admin/{target}")
    public ResultData adminReset(@RequestAttribute("userId") Long actor, @PathVariable Long target) {
        if (target <= 0 || !auth.userExists(target)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResultData.success(ResultCode.SUCCESS, client.call(target, "face.reset", Map.of("actor", actor)));
    }
}
