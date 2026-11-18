package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayOneSideErrorCodeIgnoreRow;
import com.axonlink.ai.replay.persistence.ReplayOneSideErrorCodeIgnoreDao;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class ReplayOneSideErrorCodeIgnoreExportService {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private final ReplayOneSideErrorCodeIgnoreDao dao;

    public ReplayOneSideErrorCodeIgnoreExportService(ReplayOneSideErrorCodeIgnoreDao dao) { this.dao = dao; }

    public byte[] exportReviewed() {
        List<ReplayOneSideErrorCodeIgnoreRow> rows = dao.listApprovedForExport();
        String content = "查询：\r\n528：" + join(rows, "QUERY", "528") + "\r\nccbs：" + join(rows, "QUERY", "ccbs")
                + "\r\n\r\n账务：\r\n528：" + join(rows, "ACCOUNTING", "528") + "\r\nccbs：" + join(rows, "ACCOUNTING", "ccbs") + "\r\n";
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(UTF8_BOM.length + body.length);
        out.writeBytes(UTF8_BOM); out.writeBytes(body); return out.toByteArray();
    }

    private static String join(List<ReplayOneSideErrorCodeIgnoreRow> rows, String category, String ownership) {
        return rows.stream().filter(row -> category.equals(row.category()) && ownership.equals(row.ownership()))
                .map(ReplayOneSideErrorCodeIgnoreRow::errorCode).reduce((left, right) -> left + "," + right).orElse("");
    }
}
