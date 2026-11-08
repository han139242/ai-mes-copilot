package com.neuedu.ems.service.sys;

import com.neuedu.ems.common.AuthUserInfo;
import com.neuedu.ems.entity.sys.SysUser;

/**
 * 认证服务接口
 */
public interface AuthService {
    /**
     * 用户登录
     */
    SysUser login(String username, String password);

    /** Only called by the server after independent face verification. */
    Long faceCandidate(String username);
    java.util.List<Long> faceIdentificationCandidates();
    SysUser completeFaceLogin(Long userId, boolean verified);
    boolean reauthenticate(Long userId, String password);
    boolean userExists(Long userId);

    AuthUserInfo getUserInfo(Long userId);

    void logout(Long userId);
}
