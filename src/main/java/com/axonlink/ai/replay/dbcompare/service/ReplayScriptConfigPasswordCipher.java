package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Component
public class ReplayScriptConfigPasswordCipher {
    private static final String VERSION = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final ReplayDatabaseComparisonProperties properties;

    public ReplayScriptConfigPasswordCipher(ReplayDatabaseComparisonProperties properties) {
        this.properties = properties;
    }

    SecretKey requireKey() {
        String configured = properties.getScriptConfigPasswordKey();
        if (configured == null || configured.isBlank()) throw unavailable();
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length != 32) throw unavailable();
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    String encrypt(String password, SecretKey key) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    String decrypt(String ciphertext, SecretKey key) {
        if (ciphertext == null || !ciphertext.startsWith(VERSION)) throw unavailable();
        try {
            ByteBuffer payload = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext.substring(VERSION.length())));
            if (payload.remaining() < NONCE_BYTES + TAG_BITS / Byte.SIZE) throw unavailable();
            byte[] nonce = new byte[NONCE_BYTES];
            payload.get(nonce);
            byte[] encrypted = new byte[payload.remaining()];
            payload.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, SecretKey key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    private ReplayDatabaseComparisonGenerationException unavailable() {
        return new ReplayDatabaseComparisonGenerationException(HttpStatus.SERVICE_UNAVAILABLE,
                "SCRIPT_CONFIG_PASSWORD_UNAVAILABLE", "方案密码配置不可用", Map.of());
    }
}
