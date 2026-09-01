package com.aegis.admin.config.infra;

import com.aegis.core.spi.EmbeddingService;
import com.aegis.core.spi.IObjectStorage;
import com.aegis.core.spi.IVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 依赖健康检查器。
 *
 * <p>应用启动后主动检查 MinIO、向量存储、嵌入服务等核心依赖的可用性，
 * 在日志中输出诊断信息，便于快速定位问题。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DependencyHealthChecker implements CommandLineRunner {

    private final IObjectStorage objectStorage;
    private final IVectorStore vectorStore;
    private final EmbeddingService embeddingService;

    @Override
    public void run(String... args) {
        log.info("========== 依赖健康检查开始 ==========");
        checkObjectStorage();
        checkVectorStore();
        checkEmbeddingService();
        log.info("========== 依赖健康检查结束 ==========");
    }

    private void checkObjectStorage() {
        try {
            objectStorage.upload(0L, "health-check.txt",
                    new java.io.ByteArrayInputStream("health-check".getBytes()), "text/plain");
            log.info("[MinIO] 对象存储连接正常");
        } catch (Exception e) {
            log.warn("[MinIO] 对象存储连接异常: {}", e.getMessage());
        }
    }

    private void checkVectorStore() {
        try {
            boolean available = vectorStore.ensureCollection(0L, "_health_check", 2);
            if (available) {
                log.info("[VectorStore] 向量存储连接正常");
            } else {
                log.warn("[VectorStore] 向量存储未启用（当前为 Noop 实现，文档切片将跳过向量入库）");
            }
        } catch (Exception | Error e) {
            log.warn("[VectorStore] 向量存储异常: {}（文档切片将跳过向量入库）", e.getMessage());
        }
    }

    private void checkEmbeddingService() {
        try {
            int dim = embeddingService.getDimension();
            log.info("[Embedding] 嵌入服务正常，维度={}", dim);
        } catch (Exception e) {
            log.warn("[Embedding] 嵌入服务异常: {}（文档处理将在嵌入步骤失败）", e.getMessage());
        }
    }
}
