package com.neuedu.ems.util;

/**
 * JWT常量类：统一管理JWT配置参数
 */
public class JwtConstants {

    /**
     * JWT签名密钥（重要！生产环境需加密存储，避免硬编码）
     * 建议长度≥32位，可使用随机字符串生成
     */
    public static final String JWT_SECRET = System.getenv().getOrDefault(
            "JWT_SECRET",
            "change-me-please-use-env-var-32-bytes-minimum"
    );

    /**
     * JWT过期时间（单位：秒）。默认 12 小时，可用 JWT_EXPIRE_SECONDS 覆盖。
     * 前端登录态按天保存，令牌有效期过短会让用户在使用中途被踢回登录页。
     */
    public static final long JWT_EXPIRE_TIME = resolveExpireSeconds();

    private static long resolveExpireSeconds() {
        String configured = System.getenv("JWT_EXPIRE_SECONDS");
        if (configured != null && !configured.isBlank()) {
            try {
                long seconds = Long.parseLong(configured.trim());
                if (seconds > 0) {
                    return seconds;
                }
            } catch (NumberFormatException ignored) {
                // 配置非法时退回默认值，避免应用启动失败
            }
        }
        return 43200L;
    }

    /**
     * JWT请求头名称（前端携带token的请求头）
     */
    public static final String JWT_HEADER = "Authorization";

    /**
     * JWT令牌前缀（前端需拼接：Bearer + 空格 + token）
     *
     *
     *
     */
    public static final String JWT_PREFIX = "Bearer ";
}
