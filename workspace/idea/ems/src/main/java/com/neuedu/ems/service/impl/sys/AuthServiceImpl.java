package com.neuedu.ems.service.impl.sys;

import com.neuedu.ems.common.AuthUserInfo;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.exception.AccountStatusException;
import com.neuedu.ems.mapper.sys.SysUserMapper;
import com.neuedu.ems.service.sys.AuthService;
import com.neuedu.ems.util.RedisUtil;
import com.neuedu.ems.util.PasswordHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现类
 */
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private PasswordHasher passwordHasher;

    @Value("${mes.auth.max-login-attempts:5}")
    private int maxLoginAttempts = 5;

    /** 记录一次登录失败；若因此触发了锁定或停用，立刻把可读原因抛给前端。 */
    private void handleFailedAttempt(SysUser user) {
        sysUserMapper.recordLoginFailure(user.getUserId(), Math.max(1, maxLoginAttempts));
        rejectAfterRecheck(user.getUserId());
    }

    /** 更新之后再查一次账号状态，保证并发的锁定或停用不会被静默放过。 */
    private void rejectAfterRecheck(Long userId) {
        SysUser changedUser = sysUserMapper.selectById(userId);
        if (changedUser != null) {
            validateAccountStatus(changedUser);
        }
    }

    @Override
    public SysUser login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return null;
        }
        SysUser user = sysUserMapper.getByUserName(username);
        if (user == null) {
            return null;
        }
        return authenticate(user, password);
    }

    @Override
    public Long faceCandidate(String username) {
        SysUser user = StringUtils.hasText(username) ? sysUserMapper.getByUserName(username) : null;
        if (user == null || (user.getDeletedFlag() != null && user.getDeletedFlag() != 0)) return null;
        validateAccountStatus(user);
        return user.getUserId();
    }

    @Override
    public SysUser completeFaceLogin(Long userId, boolean verified) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null || (user.getDeletedFlag() != null && user.getDeletedFlag() != 0)) return null;
        validateAccountStatus(user);
        if (!verified) { handleFailedAttempt(user); return null; }
        return completeLogin(user);
    }

    @Override
    public List<Long> faceIdentificationCandidates() {
        List<Long> ids = sysUserMapper.faceIdentificationCandidates();
        if (ids.size() > 100) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "账号数量超出本机识别范围，请使用密码登录");
        return ids;
    }

    @Override
    public boolean reauthenticate(Long userId, String password) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null || (user.getDeletedFlag() != null && user.getDeletedFlag() != 0)) return false;
        validateAccountStatus(user);
        if (!StringUtils.hasText(password) || !passwordHasher.matches(password, user.getPasswordHash())) {
            handleFailedAttempt(user); return false;
        }
        return true;
    }

    @Override
    public boolean userExists(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        return user != null && (user.getDeletedFlag() == null || user.getDeletedFlag() == 0);
    }

    /** 校验密码并完成登录；密码不符时按失败策略处理。 */
    private SysUser authenticate(SysUser user, String password) {
        validateAccountStatus(user);
        if (!passwordHasher.matches(password, user.getPasswordHash())) {
            handleFailedAttempt(user);
            return null;
        }
        if (!passwordHasher.isEncoded(user.getPasswordHash())) {
            sysUserMapper.upgradePasswordHash(user.getUserId(), passwordHasher.hash(password));
        }
        return completeLogin(user);
    }

    /** 落登录成功记录并回填缓存；并发下更新失败要重新核对账号状态。 */
    private SysUser completeLogin(SysUser user) {
        LocalDateTime loginTime = LocalDateTime.now();
        if (sysUserMapper.recordLoginSuccess(user.getUserId(), loginTime) == 0) {
            rejectAfterRecheck(user.getUserId());
            return null;
        }
        user.setLoginFailCount(0);
        user.setLockedTime(null);
        user.setLastLoginTime(loginTime);
        user.setPasswordHash(null);
        cacheUser(user);
        return user;
    }

    @Override
    public AuthUserInfo getUserInfo(Long userId) {
        SysUser user = userId == null ? null : sysUserMapper.selectById(userId);
        if (user == null || (user.getDeletedFlag() != null && user.getDeletedFlag() != 0)) {
            return null;
        }
        validateAccountStatus(user);
        List<String> roles = sysUserMapper.listRoleCodes(userId);
        boolean superAdmin = "admin".equalsIgnoreCase(user.getUsername()) || roles.contains("SUPER_ADMIN");
        if (superAdmin && !roles.contains("SUPER_ADMIN")) {
            roles = List.of("SUPER_ADMIN");
        }
        List<String> dataScopes = sysUserMapper.listDataScopes(userId);
        String dataScope = superAdmin ? "ALL" : List.of("ALL", "DEPARTMENT", "TEAM", "SELF").stream()
                .filter(dataScopes::contains)
                .findFirst()
                .orElse("SELF");
        return new AuthUserInfo(
                user.getUserId(),
                user.getUsername(),
                user.getEmployeeId(),
                user.getStatus(),
                user.getLastLoginTime(),
                roles,
                sysUserMapper.listPermissionCodes(userId),
                superAdmin,
                dataScope);
    }

    @Override
    public void logout(Long userId) {
        if (userId == null) {
            return;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        try {
            redisUtil.delete("username_" + user.getUsername());
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while clearing login cache: {}", e.getMessage());
        }
    }

    private void cacheUser(SysUser user) {
        try {
            redisUtil.set("username_" + user.getUsername(), user, 30, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            log.warn("Redis cache unavailable; continuing login without cached user: {}", e.getMessage());
        }
    }

    private void validateAccountStatus(SysUser user) {
        String status = user.getStatus();
        if ("ENABLE".equals(status)) {
            return;
        }
        if ("DISABLE".equals(status)) {
            throw new AccountStatusException(ResultCode.USER_DISABLED);
        }
        if ("LOCKED".equals(status)) {
            throw new AccountStatusException(ResultCode.USER_LOCKED);
        }
        throw new AccountStatusException(ResultCode.USER_STATUS_ERROR);
    }
}
