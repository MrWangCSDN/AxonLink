package com.axonlink.service;

/** 按领域导出完整交易链路 Excel。 */
public interface FlowtranChainExportService {

    ExportFile exportDomain(String domainKey);

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
