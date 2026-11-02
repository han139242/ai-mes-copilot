package com.neuedu.ems.security.phone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 仅从受控环境变量加载电话字段密钥，并在应用启动阶段完成格式校验。
 */
@Component
public class PhoneCryptoKeyProvider {

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final int AES_256_BYTES = 32;
    private static final int MIN_HMAC_BYTES = 32;

    private final String activeKeyId;
    private final Map<String, SecretKey> encryptionKeys;
    private final byte[] lookupHmacKey;
    private final boolean legacyReadEnabled;
    private final Function<String, String> externalPropertyResolver;

    @Autowired
    public PhoneCryptoKeyProvider(Environment environment) {
        this(environment, System::getenv, PhoneCryptoKeyProvider::readWindowsUserEnvironmentVariable);
    }

    /**
     * 测试入口：Spring 环境为空时，只使用调用方给出的解析器，不读取当前进程环境。
     */
    PhoneCryptoKeyProvider(Environment environment, Function<String, String> externalPropertyResolver) {
        this(environment, name -> null, externalPropertyResolver);
    }

    private final Function<String, String> processPropertyResolver;

    PhoneCryptoKeyProvider(Environment environment, Function<String, String> processPropertyResolver,
                           Function<String, String> externalPropertyResolver) {
        this.processPropertyResolver = processPropertyResolver;
        this.externalPropertyResolver = externalPropertyResolver;

        activeKeyId = require(environment, "PHONE_CRYPTO_ACTIVE_KEY_ID");
        validateKeyId(activeKeyId);

        Set<String> keyIds = parseKeyIds(require(environment, "PHONE_CRYPTO_KEY_IDS"));
        if (!keyIds.contains(activeKeyId)) {
            throw new IllegalStateException("PHONE_CRYPTO_KEY_IDS must include PHONE_CRYPTO_ACTIVE_KEY_ID");
        }

        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (String keyId : keyIds) {
            String encoded = require(environment, encryptionKeyVariable(keyId));
            byte[] bytes = decodeBase64(encoded, encryptionKeyVariable(keyId));
            if (bytes.length != AES_256_BYTES) {
                throw new IllegalStateException(encryptionKeyVariable(keyId) + " must decode to 32 bytes");
            }
            keys.put(keyId, new SecretKeySpec(bytes, "AES"));
        }
        encryptionKeys = Map.copyOf(keys);

        lookupHmacKey = decodeBase64(require(environment, "PHONE_LOOKUP_HMAC_KEY"), "PHONE_LOOKUP_HMAC_KEY");
        if (lookupHmacKey.length < MIN_HMAC_BYTES) {
            throw new IllegalStateException("PHONE_LOOKUP_HMAC_KEY must decode to at least 32 bytes");
        }
        legacyReadEnabled = environment.getProperty("PHONE_CRYPTO_LEGACY_READ_ENABLED", Boolean.class, false);
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public SecretKey getActiveEncryptionKey() {
        return encryptionKeys.get(activeKeyId);
    }

    public SecretKey getEncryptionKey(String keyId) {
        return encryptionKeys.get(keyId);
    }

    public byte[] getLookupHmacKey() {
        return lookupHmacKey.clone();
    }

    public boolean isLegacyReadEnabled() {
        return legacyReadEnabled;
    }

    private String require(Environment environment, String name) {
        String value = resolveProperty(environment, name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for telephone encryption");
        }
        return value.trim();
    }

    private String resolveProperty(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            value = processPropertyResolver.apply(name);
        }
        if ((value == null || value.isBlank()) && externalPropertyResolver != null) {
            value = externalPropertyResolver.apply(name);
        }
        return value;
    }

    private static Set<String> parseKeyIds(String rawKeyIds) {
        Set<String> keyIds = new LinkedHashSet<>();
        for (String candidate : rawKeyIds.split(",")) {
            String keyId = candidate.trim();
            validateKeyId(keyId);
            keyIds.add(keyId);
        }
        if (keyIds.isEmpty()) {
            throw new IllegalStateException("PHONE_CRYPTO_KEY_IDS must not be empty");
        }
        return keyIds;
    }

    private static void validateKeyId(String keyId) {
        if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalStateException("PHONE_CRYPTO key id is invalid");
        }
    }

    private static String encryptionKeyVariable(String keyId) {
        return "PHONE_CRYPTO_KEY_" + keyId.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static byte[] decodeBase64(String encoded, String variableName) {
        try {
            return Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(variableName + " must be valid Base64", e);
        }
    }

    private static String readWindowsUserEnvironmentVariable(String name) {
        if (!isWindows()) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", name)
                    .redirectErrorStream(true)
                    .start();
            return readRegistryValue(process, name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // 读不到就按未配置处理，由上层的必填校验给出明确报错。
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 从 reg query 的输出里取出目标值；进程超时或返回非零都视为未配置。 */
    private static String readRegistryValue(Process process, String name)
            throws IOException, InterruptedException {
        boolean finished = process.waitFor(2, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            return null;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s+REG_\\S+\\s+(.+)\\s*$",
                Pattern.MULTILINE);
        java.util.regex.Matcher matcher = pattern.matcher(output);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
