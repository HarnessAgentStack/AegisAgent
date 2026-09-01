package com.aegis.admin.infrastructure.sandbox.spec;

import com.aegis.admin.config.infra.SandboxK8sProperties;
import com.aegis.admin.infrastructure.sandbox.K8sClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * K8s 后端快照/恢复能力实现。
 *
 * <p>提供沙箱工作区的快照创建、恢复、删除和列表查询能力，
 * 通过 {@link K8sClusterService} 在 Pod 内执行 tar 打包/解压命令。</p>
 *
 * <h3>快照存储策略</h3>
 * <ul>
 *   <li>主路径：K8s Pod 内工作区打包 → 共享存储（本地/NFS）</li>
 *   <li>fallback：K8s 不可用时回退到本地文件系统存储</li>
 *   <li>存储路径：{@code aegis.snapshot.storage-path} 配置，默认 /data/aegis/snapshots</li>
 * </ul>
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #createSnapshot(String, String)} — 在 Pod 内 tar -czf 打包工作区</li>
 *   <li>{@link #restoreSnapshot(String, String, String)} — 在 Pod 内 tar -xzf 恢复工作区</li>
 *   <li>{@link #deleteSnapshot(String)} — 清理快照存储</li>
 *   <li>{@link #listSnapshots(String)} — 查询可用快照列表</li>
 * </ul>
 *
 * @author wang.zhen
 * @see K8sClusterService
 * @see SandboxK8sProperties
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class K8sSnapshotSpec {

    private final K8sClusterService k8sClusterService;
    private final SandboxK8sProperties k8sProperties;

    @Value("${aegis.snapshot.storage-path:/data/aegis/snapshots}")
    private String storagePath;

    private static final DateTimeFormatter SNAPSHOT_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 创建快照（在 Pod 内 tar -czf 打包工作区）。
     *
     * @param sandboxId  沙箱实例 ID（对应 podName）
     * @param targetPath 工作区路径（Pod 内路径，如 /workspace）
     * @return 快照 ID，失败返回 null
     */
    public String createSnapshot(String sandboxId, String targetPath) {
        String snapshotId = generateSnapshotId();
        String namespace = resolveNamespace();
        String podName = sandboxId;

        log.info("[snapshot] 创建快照: sandboxId={}, targetPath={}, snapshotId={}",
                sandboxId, targetPath, snapshotId);

        if (k8sClusterService.isAvailable()) {
            try {
                ensureDirectoryExists(storagePath);

                String tarFileName = snapshotId + ".tar.gz";
                String podTarPath = "/tmp/" + tarFileName;

                String command = String.format(
                        "cd %s && tar -czf %s .",
                        targetPath, podTarPath);

                String result = k8sClusterService.execInPod(namespace, podName, command);
                if (result == null) {
                    log.warn("[snapshot] K8s 打包命令执行失败，回退到本地存储: sandboxId={}", sandboxId);
                    return fallbackCreateSnapshot(snapshotId, sandboxId, targetPath);
                }

                log.info("[snapshot] Pod 内打包完成: podName={}, tarPath={}", podName, podTarPath);

                String copyCommand = String.format(
                        "cp %s %s/%s",
                        podTarPath, storagePath, tarFileName);
                k8sClusterService.execInPod(namespace, podName, copyCommand);

                cleanupTempFile(namespace, podName, podTarPath);

                log.info("[snapshot] 快照创建成功: snapshotId={}", snapshotId);
                return snapshotId;
            } catch (Exception e) {
                log.error("[snapshot] K8s 快照创建异常，回退到本地存储: sandboxId={}", sandboxId, e);
                return fallbackCreateSnapshot(snapshotId, sandboxId, targetPath);
            }
        } else {
            log.warn("[snapshot] K8s 不可用，使用本地文件系统存储: sandboxId={}", sandboxId);
            return fallbackCreateSnapshot(snapshotId, sandboxId, targetPath);
        }
    }

    /**
     * 从快照恢复工作区（tar -xzf）。
     *
     * @param sandboxId  沙箱实例 ID
     * @param snapshotId 快照 ID
     * @param targetPath 目标工作区路径
     * @return 是否恢复成功
     */
    public boolean restoreSnapshot(String sandboxId, String snapshotId, String targetPath) {
        String namespace = resolveNamespace();
        String podName = sandboxId;

        log.info("[snapshot] 恢复快照: sandboxId={}, snapshotId={}, targetPath={}",
                sandboxId, snapshotId, targetPath);

        if (k8sClusterService.isAvailable()) {
            try {
                String tarFileName = snapshotId + ".tar.gz";
                String localTarPath = storagePath + File.separator + tarFileName;
                String podTarPath = "/tmp/" + tarFileName;

                String copyToPod = String.format("cp %s %s", localTarPath, podTarPath);
                k8sClusterService.execInPod(namespace, podName, copyToPod);

                String restoreCmd = String.format(
                        "mkdir -p %s && cd %s && tar -xzf %s",
                        targetPath, targetPath, podTarPath);
                String result = k8sClusterService.execInPod(namespace, podName, restoreCmd);

                cleanupTempFile(namespace, podName, podTarPath);

                if (result != null) {
                    log.info("[snapshot] 快照恢复成功: snapshotId={}, sandboxId={}", snapshotId, sandboxId);
                    return true;
                } else {
                    log.warn("[snapshot] K8s 恢复命令执行失败，回退到本地恢复: snapshotId={}", snapshotId);
                    return fallbackRestoreSnapshot(snapshotId, targetPath);
                }
            } catch (Exception e) {
                log.error("[snapshot] K8s 快照恢复异常，回退到本地: snapshotId={}", snapshotId, e);
                return fallbackRestoreSnapshot(snapshotId, targetPath);
            }
        } else {
            log.warn("[snapshot] K8s 不可用，使用本地恢复: snapshotId={}", snapshotId);
            return fallbackRestoreSnapshot(snapshotId, targetPath);
        }
    }

    /**
     * 删除快照。
     *
     * @param snapshotId 快照 ID
     * @return 是否删除成功
     */
    public boolean deleteSnapshot(String snapshotId) {
        log.info("[snapshot] 删除快照: snapshotId={}", snapshotId);
        try {
            String tarFileName = snapshotId + ".tar.gz";
            Path snapshotFile = Paths.get(storagePath, tarFileName);

            if (Files.exists(snapshotFile)) {
                Files.delete(snapshotFile);
                log.info("[snapshot] 快照文件已删除: snapshotId={}, path={}", snapshotId, snapshotFile);
                return true;
            } else {
                log.debug("[snapshot] 快照文件不存在: snapshotId={}", snapshotId);
                return true;
            }
        } catch (IOException e) {
            log.error("[snapshot] 快照删除失败: snapshotId={}", snapshotId, e);
            return false;
        }
    }

    /**
     * 查询沙箱实例的可用快照列表。
     *
     * @param sandboxId 沙箱实例 ID
     * @return 快照 ID 列表
     */
    public List<String> listSnapshots(String sandboxId) {
        log.debug("[snapshot] 查询快照列表: sandboxId={}", sandboxId);
        List<String> snapshots = new ArrayList<>();
        try {
            Path storageDir = Paths.get(storagePath);
            if (!Files.exists(storageDir)) {
                return snapshots;
            }

            try (var stream = Files.list(storageDir)) {
                stream.filter(p -> p.toString().endsWith(".tar.gz"))
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return name.substring(0, name.length() - ".tar.gz".length());
                        })
                        .forEach(snapshots::add);
            }
            log.debug("[snapshot] 快照列表: sandboxId={}, count={}", sandboxId, snapshots.size());
        } catch (IOException e) {
            log.error("[snapshot] 快照列表查询失败: sandboxId={}", sandboxId, e);
        }
        return snapshots;
    }

    // =========================================================================
    // Fallback：本地文件系统存储
    // =========================================================================

    private String fallbackCreateSnapshot(String snapshotId, String sandboxId, String targetPath) {
        try {
            String localPath = getSnapshotPath(snapshotId);
            ensureDirectoryExists(storagePath);

            Path sourcePath = Paths.get(targetPath);
            Path targetFile = Paths.get(localPath);

            if (Files.exists(sourcePath)) {
                Path rawParent = targetFile.getParent();
                final Path parentDir = rawParent != null ? rawParent : Paths.get(".");
                try (var stream = Files.walk(sourcePath)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(source -> {
                                try {
                                    Path relativePath = sourcePath.relativize(source);
                                    Path target = parentDir.resolve(relativePath);
                                    Files.createDirectories(target.getParent());
                                    Files.copy(source, target,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException ex) {
                                    log.warn("[snapshot] fallback 文件复制失败: {}", source, ex);
                                }
                            });
                }
                log.info("[snapshot] fallback 快照创建成功: snapshotId={}, path={}", snapshotId, localPath);
            } else {
                log.warn("[snapshot] fallback 源路径不存在: targetPath={}", targetPath);
            }
            return snapshotId;
        } catch (Exception e) {
            log.error("[snapshot] fallback 快照创建失败: sandboxId={}", sandboxId, e);
            return null;
        }
    }

    private boolean fallbackRestoreSnapshot(String snapshotId, String targetPath) {
        try {
            String localPath = getSnapshotPath(snapshotId);
            Path sourceFile = Paths.get(localPath);

            if (!Files.exists(sourceFile)) {
                log.warn("[snapshot] fallback 快照文件不存在: snapshotId={}", snapshotId);
                return false;
            }

            Path targetDir = Paths.get(targetPath);
            Files.createDirectories(targetDir);

            try (var stream = Files.walk(sourceFile.getParent())) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".tar.gz"))
                        .forEach(source -> {
                            try {
                                Path relativePath = sourceFile.getParent().relativize(source);
                                Path target = targetDir.resolve(relativePath);
                                Files.createDirectories(target.getParent());
                                Files.copy(source, target,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException ex) {
                                log.warn("[snapshot] fallback 文件恢复失败: {}", source, ex);
                            }
                        });
            }

            log.info("[snapshot] fallback 快照恢复成功: snapshotId={}, targetPath={}", snapshotId, targetPath);
            return true;
        } catch (Exception e) {
            log.error("[snapshot] fallback 快照恢复失败: snapshotId={}", snapshotId, e);
            return false;
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private String generateSnapshotId() {
        String timestamp = LocalDateTime.now().format(SNAPSHOT_TS_FMT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "snap-" + timestamp + "-" + uuid;
    }

    private String getSnapshotPath(String snapshotId) {
        return storagePath + File.separator + snapshotId + ".tar.gz";
    }

    private void ensureDirectoryExists(String path) {
        try {
            Files.createDirectories(Paths.get(path));
        } catch (IOException e) {
            log.warn("[snapshot] 存储目录创建失败: path={}", path, e);
        }
    }

    private void cleanupTempFile(String namespace, String podName, String tempPath) {
        try {
            k8sClusterService.execInPod(namespace, podName, "rm -f " + tempPath);
        } catch (Exception e) {
            log.debug("[snapshot] 临时文件清理失败（忽略）: pod={}, path={}", podName, tempPath);
        }
    }

    private String resolveNamespace() {
        var k8sConfig = k8sProperties.getK8s();
        String kubeconfig = k8sConfig.getKubeconfig();
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            return kubeconfig;
        }
        return "default";
    }
}