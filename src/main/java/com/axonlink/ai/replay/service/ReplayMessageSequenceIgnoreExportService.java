package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayMessageSequenceIgnoreRow;
import com.axonlink.ai.replay.persistence.ReplayMessageSequenceIgnoreDao;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class ReplayMessageSequenceIgnoreExportService {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private final ReplayMessageSequenceIgnoreDao dao;

    public ReplayMessageSequenceIgnoreExportService(ReplayMessageSequenceIgnoreDao dao) {
        this.dao = dao;
    }

    public byte[] exportReviewed() {
        List<ReplayMessageSequenceIgnoreRow> rows = dao.listApprovedForExport();
        String query = join(rows, "QUERY");
        String accounting = join(rows, "ACCOUNTING");
        byte[] content = ("查询：" + query + "\r\n账务：" + accounting + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(UTF8_BOM.length + content.length);
        out.writeBytes(UTF8_BOM);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static String join(List<ReplayMessageSequenceIgnoreRow> rows, String category) {
        return rows.stream().filter(row -> category.equals(row.category()))
                .map(ReplayMessageSequenceIgnoreRow::messageSequence)
                .reduce((left, right) -> left + "," + right).orElse("");
    }
}
