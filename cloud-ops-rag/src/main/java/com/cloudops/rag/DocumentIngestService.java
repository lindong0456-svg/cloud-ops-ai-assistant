package com.cloudops.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文档入库服务
 *
 * 数据流：读取 .md 文件 → 按字符分块(800/150) → 调百炼向量化 → 写入 Milvus
 *
 * 借鉴联通中间表预聚合思路：
 *   联通把原始大表预聚合到中间表加速查询；
 *   这里把原始文档预计算成向量存 Milvus，查询时不实时算 embedding。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.chunk-size:800}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:150}")
    private int chunkOverlap;

    @Value("${rag.docs-path:./docs/runbooks}")
    private String docsPath;

    /**
     * 入库全部文档 — 项目启动后手动调一次
     */
    public int ingestAll() {
        log.info("开始文档入库，文档目录: {}", docsPath);

        // 1. 加载所有 .md 文件
        List<Document> documents = loadDocuments(docsPath);
        log.info("加载到 {} 篇文档", documents.size());

        // 2. 分块器（beta3 用 DocumentSplitters 工具类）
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

        // 3. 构建入库器（分块 + 向量化 + 存储），设置批量大小为 10 以符合百炼 API 限制
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        // 4. 逐篇执行入库，并对每篇文档的分块结果再次分批处理（每批最多 10 个片段）
        int totalProcessed = 0;
        
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            
            log.info("处理文档 [{}/{}]: {}", i + 1, documents.size(), doc.metadata().getString("source"));
            
            try {
                // 先分块
                List<TextSegment> segments = splitter.split(doc);
                log.info("文档分块完成，共 {} 个片段", segments.size());
                
                // 分批向量化和存储（每批最多 10 个片段）
                int segmentBatchSize = 10;
                for (int j = 0; j < segments.size(); j += segmentBatchSize) {
                    int endIndex = Math.min(j + segmentBatchSize, segments.size());
                    List<TextSegment> segmentBatch = segments.subList(j, endIndex);
                    
                    log.info("处理片段批次 [{}/{}]", j / segmentBatchSize + 1, (segments.size() + segmentBatchSize - 1) / segmentBatchSize);
                    
                    // 直接嵌入并存储这一批片段
                    List<dev.langchain4j.data.embedding.Embedding> embeddings = embeddingModel.embedAll(segmentBatch).content();
                    // 一次批量写入，1 次网络请求
                    embeddingStore.addAll(embeddings, segmentBatch);
                }
                
                totalProcessed++;
                log.info("文档入库成功，已处理 {}/{} 篇文档", totalProcessed, documents.size());
            } catch (Exception e) {
                log.error("文档处理失败 [{}]: {}", doc.metadata().getString("source"), e.getMessage(), e);
                throw new RuntimeException("文档入库失败", e);
            }
        }

        log.info("文档入库完成，共处理 {} 篇文档", totalProcessed);
        return totalProcessed;
    }

    /**
     * 加载目录下所有 Markdown 文件
     */
    private List<Document> loadDocuments(String dirPath) {
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(dirPath))) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".md"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         String content = Files.readString(p);
                         Metadata metadata = new Metadata();
                         metadata.put("source", p.getFileName().toString());

                         // ★ 新增: 权限标签
                         DocumentPathMapping mapping = resolvePathMapping(p);
                         metadata.put("tenant_id", mapping.tenantId());
                         metadata.put("dept_id", mapping.deptId());
                         metadata.put("access_level", mapping.accessLevel());
                         metadata.put("category", mapping.category());

                         Document doc = Document.from(content, metadata);
                         log.info("加载文档: {} ({}字符, 权限: {}/{})",
                                 p.getFileName(), content.length(), mapping.accessLevel(), mapping.tenantId());

                         documents.add(doc);
                         log.info("加载文档: {} ({}字符)", p.getFileName(), content.length());
                     } catch (IOException e) {
                         log.error("读取文档失败: {}", p, e);
                     }
                 });
        } catch (IOException e) {
            log.error("遍历文档目录失败: {}", dirPath, e);
        }
        return documents;
    }

    /**
     * 根据文档路径解析权限标签
     *
     * 路径模式:
     *   docs/runbooks/alert-cpu-high.md
     *     → public（全局通用SOP）
     *
     *   docs/runbooks/tenant-001/xxx.md
     *     → tenant级，tenant-001可见
     *
     *   docs/runbooks/tenant-001/ops/xxx.md
     *     → dept级，tenant-001的dept-prod-ops可见
     *
     *   docs/runbooks/tenant-003/xxx.md
     *     → tenant级，tenant-003可见
     */
    private DocumentPathMapping resolvePathMapping(Path docPath) {
        String relativePath = docPath.toString()
                .replace(docsPath, "")
                .replace("\\", "/");

        // 去掉开头的 /
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        String[] parts = relativePath.split("/");

        // 情况1: 直接在 runbooks/ 下 → public
        if (parts.length == 1) {
            return new DocumentPathMapping(
                    "public", "public", "public", resolveCategory(parts[0])
            );
        }

        // 情况2: tenant-xxx/xxx.md → tenant级
        if (parts.length == 2) {
            String tenantId = parts[0];  // "tenant-001"
            return new DocumentPathMapping(
                    tenantId, "public", "tenant", resolveCategory(parts[1])
            );
        }

        // 情况3: tenant-xxx/dept-xxx/xxx.md → dept级
        if (parts.length >= 3) {
            String tenantId = parts[0];  // "tenant-001"
            String deptFolder = parts[1]; // "ops" → 映射为 "dept-prod-ops"
            String deptId = mapDeptFolder(tenantId, deptFolder);
            return new DocumentPathMapping(
                    tenantId, deptId, "dept", resolveCategory(parts[2])
            );
        }

        // 默认: public
        return new DocumentPathMapping("public", "public", "public", "general");
    }

    /**
     * 从文件名推断文档分类
     */
    private String resolveCategory(String fileName) {
        String name = fileName.toLowerCase();
        if (name.startsWith("alert-")) return "alarm";
        if (name.startsWith("docker-")) return "docker";
        if (name.startsWith("k8s-")) return "k8s";
        if (name.startsWith("sop-")) return "sop";
        return "general";
    }

    /**
     * 部门文件夹名 → 部门ID映射
     */
    private String mapDeptFolder(String tenantId, String folder) {
        // 简化映射: folder "ops" → "{tenant}-ops" 格式
        // 如 tenant-001/ops/ → dept-prod-ops
        return switch (tenantId) {
            case "tenant-001" -> switch (folder) {
                case "ops" -> "dept-prod-ops";
                case "fin" -> "dept-prod-fin";
                default -> "dept-prod-ops";
            };
            case "tenant-002" -> "dept-test-ops";
            case "tenant-003" -> "dept-ai-ops";
            case "tenant-004" -> "dept-bigdata-ops";
            default -> "dept-prod-ops";
        };
    }

}
