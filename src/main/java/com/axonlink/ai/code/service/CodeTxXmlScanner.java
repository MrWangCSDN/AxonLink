package com.axonlink.ai.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 从仓库 XML 文件中提取交易码映射，为 code_tx_file_map 提供数据源。
 *
 * <p>只解析 {@code *.flowtrans.xml}（Sunline 平台交易流程定义文件）：
 * 根元素为 {@code <flowtran id="TC0076" ...>}，交易码取 {@code id} 属性。
 * serviceType / pbs / pcs 等服务元数据 XML 不含交易码，不在此解析。
 *
 * <p>XML 解析启用安全特性（禁止 DOCTYPE / 外部实体），防止 XXE。
 */
@Component
public class CodeTxXmlScanner {

    private static final Logger log = LoggerFactory.getLogger(CodeTxXmlScanner.class);

    private static final String FLOWTRANS_SUFFIX = ".flowtrans.xml";

    /**
     * 扫描仓库跟踪文件，解析 *.flowtrans.xml 提取 tx_id。
     *
     * @param localDir     仓库本地工作目录（git 工作区根）
     * @param trackedFiles git ls-files 得到的全量仓库相对路径（不经扩展名过滤）
     * @return 每项 = [repoRelativeFilePath, txId]
     */
    public List<String[]> scan(File localDir, List<String> trackedFiles) {
        if (localDir == null || trackedFiles == null || trackedFiles.isEmpty()) {
            return List.of();
        }
        DocumentBuilderFactory factory = safeFactory();
        List<String[]> result = new ArrayList<>();
        long flowtransCount = 0;
        for (String path : trackedFiles) {
            if (!path.toLowerCase().endsWith(FLOWTRANS_SUFFIX)) {
                continue;
            }
            flowtransCount++;
            File f = new File(localDir, path);
            if (!f.exists() || f.length() == 0) {
                continue;
            }
            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setErrorHandler(null); // 静默 SAX 格式警告，不终止解析
                Document doc = builder.parse(f);
                Element root = doc.getDocumentElement();
                if (root == null || !"flowtran".equals(root.getTagName())) {
                    continue;
                }
                String txId = root.getAttribute("id");
                if (txId != null && !txId.isBlank()) {
                    result.add(new String[]{path, txId.trim()});
                }
            } catch (Exception e) {
                log.debug("跳过解析失败的 flowtrans XML {} : {}", path, e.getMessage());
            }
        }
        log.info("flowtrans XML 扫描：发现 {} 个 .flowtrans.xml，成功提取交易码 {} 条",
                flowtransCount, result.size());
        return result;
    }

    /** 安全 XML 工厂：禁止 DOCTYPE / 外部实体（防 XXE 注入）。 */
    private static DocumentBuilderFactory safeFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {
            // 部分 XML 实现不支持所有特性，尽量设置
        }
        f.setExpandEntityReferences(false);
        return f;
    }
}
