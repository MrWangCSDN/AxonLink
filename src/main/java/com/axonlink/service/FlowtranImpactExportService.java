package com.axonlink.service;

/**
 * 影响分析 Excel 导出服务。
 */
public interface FlowtranImpactExportService {

    ExportFile exportSingle(String mode, String id);

    ExportFile exportAll(String mode);

    void clearAllCache(String reason);

    java.util.Map<String, Object> rebuildAllCache();

    final class ExportFile {
        private final String fileName;
        private final byte[] content;

        public ExportFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getContent() {
            return content;
        }
    }
}
