package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareField;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareRegistration;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ReplayDatabaseComparisonConfigurationHasher {

    private final ReplayDatabaseComparisonConditionCodec conditionCodec =
            new ReplayDatabaseComparisonConditionCodec();

    public String hash(List<ReplayDbCompareRegistration> registrations) {
        MessageDigest digest = sha256();
        List<ReplayDbCompareRegistration> ordered = registrations.stream()
                .sorted(Comparator
                        .comparing((ReplayDbCompareRegistration item) -> normalize(item.schemaName()))
                        .thenComparing(item -> normalize(item.tableName())))
                .toList();
        putInt(digest, ordered.size());
        for (ReplayDbCompareRegistration registration : ordered) {
            putLong(digest, registration.id());
            putLong(digest, registration.version());
            putString(digest, normalize(registration.schemaName()));
            putString(digest, normalize(registration.tableName()));
            putString(digest, registration.tableComment());
            putString(digest, registration.domainName());
            putString(digest, registration.reviserEmpNo());
            putString(digest, registration.reviserUsername());
            putString(digest, registration.reviserName());
            putString(digest, registration.groupOwnerEmpNo());
            putString(digest, registration.groupOwnerName());
            putString(digest, registration.registeredDate() == null
                    ? null : registration.registeredDate().toString());
            putString(digest, conditionCodec.encode(registration.whereCondition()));
            putString(digest, registration.compiledWhereSql());
            putLong(digest, registration.compareLimit());
            putInt(digest, registration.partitionNum());
            List<ReplayDbCompareField> fields = registration.fields().stream()
                    .sorted(Comparator.comparingInt(ReplayDbCompareField::comparisonOrder)
                            .thenComparing(field -> normalize(field.columnName())))
                    .toList();
            putInt(digest, fields.size());
            for (ReplayDbCompareField field : fields) {
                putString(digest, normalize(field.columnName()));
                putString(digest, field.columnComment());
                putInt(digest, field.ordinalPosition());
                putInt(digest, field.primaryKey() ? 1 : 0);
                putNullableInt(digest, field.primaryKeyOrder());
                putInt(digest, field.comparisonOrder());
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private void putString(MessageDigest digest, String value) {
        if (value == null) {
            putInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private void putLong(MessageDigest digest, Long value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private void putNullableInt(MessageDigest digest, Integer value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        putInt(digest, value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
