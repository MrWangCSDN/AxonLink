package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareConfigScriptStatus;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionDao;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayDatabaseComparisonVersionScriptDao;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Service
public class ReplayDatabaseComparisonConfigScriptService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ReplayDatabaseComparisonVersionDao versionDao;
    private final ReplayDatabaseComparisonVersionScriptDao scriptDao;
    private final ReplayDatabaseComparisonConfigScriptGenerator generator;
    private final Clock clock;

    @Autowired
    public ReplayDatabaseComparisonConfigScriptService(
            ReplayDatabaseComparisonVersionDao versionDao,
            ReplayDatabaseComparisonVersionScriptDao scriptDao,
            ReplayDatabaseComparisonConfigScriptGenerator generator) {
        this(versionDao, scriptDao, generator, Clock.system(SHANGHAI));
    }

    ReplayDatabaseComparisonConfigScriptService(
            ReplayDatabaseComparisonVersionDao versionDao,
            ReplayDatabaseComparisonVersionScriptDao scriptDao,
            ReplayDatabaseComparisonConfigScriptGenerator generator,
            Clock clock) {
        this.versionDao = versionDao;
        this.scriptDao = scriptDao;
        this.generator = generator;
        this.clock = clock;
    }

    public ReplayDbCompareConfigScriptStatus status(String versionNo) {
        ReplayDatabaseComparisonVersionDao.StoredVersion version = requireVersion(versionNo);
        ReplayDatabaseComparisonVersionScriptDao.StoredScript script =
                scriptDao.findByVersionId(version.id());
        if (script == null) {
            return new ReplayDbCompareConfigScriptStatus(
                    false, null, null, null, null, null, null);
        }
        return new ReplayDbCompareConfigScriptStatus(
                true,
                script.fileName(),
                script.scriptSize(),
                script.scriptSha256(),
                script.generatedBy(),
                script.generatedName(),
                script.generatedAt());
    }

    public ScriptFile generate(String versionNo, ReplayIssueOperator operator) {
        ReplayDatabaseComparisonVersionDao.StoredVersion version = requireVersion(versionNo);
        ReplayDatabaseComparisonVersionScriptDao.StoredScript existing =
                scriptDao.findByVersionId(version.id());
        if (existing != null) {
            return verifiedFile(existing);
        }
        String generatedBy = requireText(operator == null ? null : operator.username(), "用户未登录");
        String generatedName = operator.realName() == null || operator.realName().isBlank()
                ? generatedBy : operator.realName().trim();
        ReplayDatabaseComparisonConfigScriptGenerator.GeneratedScript generated = generator.generate(
                versionNo, versionDao.findCompleteSnapshot(version.id()));
        ReplayDatabaseComparisonVersionScriptDao.NewScript newScript =
                new ReplayDatabaseComparisonVersionScriptDao.NewScript(
                        version.id(),
                        "replay-db-compare-config-" + versionNo + ".sql",
                        "GZIP",
                        generated.gzipContent(),
                        generated.sha256(),
                        generated.scriptSize(),
                        generated.compressedSize(),
                        generatedBy,
                        generatedName,
                        LocalDateTime.now(clock));
        try {
            scriptDao.insert(newScript);
            return new ScriptFile(newScript.fileName(), newScript.scriptSha256(), generated.sqlContent());
        } catch (DuplicateKeyException exception) {
            ReplayDatabaseComparisonVersionScriptDao.StoredScript winner =
                    scriptDao.findByVersionId(version.id());
            if (winner == null) {
                throw exception;
            }
            return verifiedFile(winner);
        }
    }

    public ScriptFile download(String versionNo) {
        ReplayDatabaseComparisonVersionDao.StoredVersion version = requireVersion(versionNo);
        ReplayDatabaseComparisonVersionScriptDao.StoredScript script =
                scriptDao.findByVersionId(version.id());
        if (script == null) {
            throw error(HttpStatus.NOT_FOUND, "CONFIG_SCRIPT_NOT_GENERATED",
                    "指定版本尚未生成生产配置脚本");
        }
        return verifiedFile(script);
    }

    private ReplayDatabaseComparisonVersionDao.StoredVersion requireVersion(String versionNo) {
        ReplayDatabaseComparisonVersionDao.StoredVersion version = versionDao.findStoredVersion(versionNo);
        if (version == null) {
            throw error(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "版本不存在");
        }
        return version;
    }

    private ScriptFile verifiedFile(ReplayDatabaseComparisonVersionScriptDao.StoredScript script) {
        if (!"GZIP".equals(script.compression())) {
            throw corrupted();
        }
        byte[] content;
        try (GZIPInputStream input = new GZIPInputStream(
                new ByteArrayInputStream(script.scriptContent()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            content = output.toByteArray();
        } catch (IOException exception) {
            throw corrupted();
        }
        if (content.length != script.scriptSize()
                || !sha256(content).equalsIgnoreCase(script.scriptSha256())) {
            throw corrupted();
        }
        return new ScriptFile(script.fileName(), script.scriptSha256(), content);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private ReplayDatabaseComparisonGenerationException corrupted() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "CONFIG_SCRIPT_CORRUPTED",
                "已保存的生产配置脚本校验失败，拒绝下载");
    }

    private ReplayDatabaseComparisonGenerationException error(
            HttpStatus status,
            String errorCode,
            String message) {
        return new ReplayDatabaseComparisonGenerationException(
                status, errorCode, message, Map.of());
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public record ScriptFile(String fileName, String sha256, byte[] content) {
    }
}
