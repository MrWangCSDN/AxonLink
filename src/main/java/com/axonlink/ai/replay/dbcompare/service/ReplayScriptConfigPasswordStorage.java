package com.axonlink.ai.replay.dbcompare.service;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** New values are explicitly plaintext; the existing AES reader is retained only for old records. */
final class ReplayScriptConfigPasswordStorage {
    private static final String PLAIN_PREFIX = "plain:v1:";
    private final ReplayScriptConfigPasswordCipher legacyCipher;

    ReplayScriptConfigPasswordStorage(ReplayScriptConfigPasswordCipher legacyCipher) {
        this.legacyCipher = legacyCipher;
    }

    String store(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请为当前数据源提供密码");
        }
        return PLAIN_PREFIX + password;
    }

    String read(String stored) {
        if (stored != null && stored.startsWith(PLAIN_PREFIX) && stored.length() > PLAIN_PREFIX.length()) {
            return stored.substring(PLAIN_PREFIX.length());
        }
        if (stored != null && stored.startsWith("v1:")) {
            try {
                return legacyCipher.decrypt(stored, legacyCipher.requireKey());
            } catch (ReplayDatabaseComparisonGenerationException unavailable) {
                throw reentryRequired();
            }
        }
        throw reentryRequired();
    }

    private ReplayDatabaseComparisonGenerationException reentryRequired() {
        return new ReplayDatabaseComparisonGenerationException(HttpStatus.UNPROCESSABLE_ENTITY,
                "SCRIPT_CONFIG_PASSWORD_REENTRY_REQUIRED", "已保存的旧密码无法读取，请重新填写数据库密码后重试", Map.of());
    }
}
