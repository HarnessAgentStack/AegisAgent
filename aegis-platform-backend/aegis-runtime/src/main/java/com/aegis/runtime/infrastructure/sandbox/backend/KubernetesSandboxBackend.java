package com.aegis.runtime.infrastructure.sandbox.backend;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * K8s 沙箱后端实现。
 *
 * <p>实现 {@link ISandboxBackend} SPI，对接 K8s Pod 资源。
 * 当 {@code aegis.runtime.sandbox.backend=k8s} 时激活。
 *
 * <h3>★ runtime 运行时使用的方法</h3>
 * <ul>
 *   <li>{@link #exec} - 在 Pod 内执行命令（runtime 主要使用）</li>
 *   <li>{@link #snapshot} - 工作区快照到 MinIO（释放时保存）</li>
 *   <li>{@link #probeAlive} - 检查 Pod Phase == Running（健康检查）</li>
 * </ul>
 *
 * <h3>★ 仅供 admin 调用的方法（runtime 不主动调用）</h3>
 * <ul>
 *   <li>{@link #create} - 预热时创建 Pod（admin 触发，runtime 不创建）</li>
 *   <li>{@link #destroy} - 回收时销毁 Pod（admin 执行，runtime 释放只标记 IDLE）</li>
 *   <li>{@link #restore} - 从快照恢复（admin 触发，runtime 不调用）</li>
 * </ul>
 *
 * <p>这些方法保留实现是为了 ISandboxBackend 接口完整性，
 * runtime 的业务代码（Coordinator）不会调用 create/destroy/restore。
 *
 * <h3>instanceId 格式</h3>
 * <p>{@code namespace/podName}（如 {@code aegis-sbx-t0-standard/sbx-a1b2c3d4}）
 *
 * <h3>K8s 不可用时的降级策略</h3>
 * <p>当 K8s 集群不可用时（{@code KubernetesClient} 为 null），Bean 仍可创建，
 * 但所有操作将抛出 {@link IllegalStateException}，阻止静默回退到其他后端。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aegis.runtime.sandbox.backend", havingValue = "k8s")
public class KubernetesSandboxBackend implements ISandboxBackend {

    /**
     * K8s 客户端，可能为 null（K8s 集群不可用时）。
     * Bean 仍可创建以避免静默回退到 Process 后端，
     * 但所有操作将抛出明确异常。
     */
    @Autowired(required = false)
    private KubernetesClient k8sClient;

    private final MinioSnapshotClient minioSnapshotClient;

    /** 默认 namespace 前缀（与 admin 池配置一致） */
    private static final String NS_PREFIX = "aegis-sbx-t";

    public KubernetesSandboxBackend(MinioSnapshotClient minioSnapshotClient) {
        this.minioSnapshotClient = minioSnapshotClient;
        log.info("KubernetesSandboxBackend 已激活（backend=k8s），KubernetesClient 状态: {}",
                k8sClient != null ? "可用" : "不可用（K8s 集群未连接）");
    }

    /**
     * 检查 K8s 客户端是否可用。
     *
     * @throws IllegalStateException 当 K8s 不可用时
     */
    private void assertK8sAvailable() {
        if (k8sClient == null) {
            throw new IllegalStateException(
                    "K8s 沙箱后端不可用: KubernetesClient 未初始化（K8s 集群可能未连接）。"
                    + "请检查 aegis.runtime.sandbox.k8s.kubeconfig 配置或 K8s 集群状态。"
                    + "当前配置 aegis.runtime.sandbox.backend=k8s，系统不会回退到其他后端。");
        }
    }

    @Override
    public String create(Long tenantId, String image, double cpu, int memoryMb) {
        assertK8sAvailable();
        String namespace = resolveNamespace(tenantId);
        String podName = "sbx-" + UUID.randomUUID().toString().substring(0, 8);
        return createPod(namespace, podName, image, cpu, memoryMb,
                Map.of("app", "aegis-sandbox", "tenant", String.valueOf(tenantId)));
    }

    /**
     * P0-2/S-G3：在指定池命名空间内创建沙箱 Pod（池内动态扩容）。
     *
     * <p>与 admin 预热创建的 Pod 保持一致：命名空间取自 sbx_pool.namespace，
     * 镜像取自池关联 sbx_base_image，标签携带 tenant/pool 归属标识，
     * 确保 admin Reconcile 可按池纳管（健康检查、回收还原、缩容销毁）。
     */
    @Override
    public String createInPool(Long tenantId, String namespace, String image,
                                double cpu, int memoryMb, java.util.Map<String, String> labels) {
        assertK8sAvailable();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("池命名空间不能为空（池内创建必须归属池命名空间）");
        }
        String podName = "sbx-" + UUID.randomUUID().toString().substring(0, 8);

        java.util.Map<String, String> podLabels = new java.util.HashMap<>();
        podLabels.put("app", "aegis-sandbox");
        podLabels.put("tenant", String.valueOf(tenantId));
        if (labels != null) {
            podLabels.putAll(labels);
        }
        return createPod(namespace, podName, image, cpu, memoryMb, podLabels);
    }

    /**
     * 创建 Pod 并等待 Running（create / createInPool 共用）。
     *
     * @return namespace/podName 格式的实例 ID
     */
    private String createPod(String namespace, String podName, String image,
                              double cpu, int memoryMb, java.util.Map<String, String> labels) {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName("sandbox")
                        .withImage(image)
                        .withCommand("sleep", "infinity")
                        .withNewResources()
                            .addToLimits("cpu", new Quantity(formatCpu(cpu)))
                            .addToLimits("memory", new Quantity(memoryMb + "Mi"))
                        .endResources()
                        .addNewVolumeMount()
                            .withName("workspace")
                            .withMountPath("/workspace")
                        .endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                        .withName("workspace")
                        .withNewEmptyDir().endEmptyDir()
                    .endVolume()
                .endSpec()
                .build();

        k8sClient.pods().inNamespace(namespace).resource(pod).create();

        k8sClient.pods().inNamespace(namespace).withName(podName)
                .waitUntilCondition(p -> p.getStatus() != null
                        && "Running".equals(p.getStatus().getPhase()),
                        60, TimeUnit.SECONDS);

        log.info("Pod 已创建: {}/{}", namespace, podName);
        return namespace + "/" + podName;
    }

    @Override
    public boolean destroy(Long tenantId, String instanceId) {
        assertK8sAvailable();
        String[] parts = parseInstanceId(instanceId);
        try {
            k8sClient.pods().inNamespace(parts[0]).withName(parts[1]).delete();
            log.info("Pod 已删除: {}", instanceId);
            return true;
        } catch (Exception e) {
            log.warn("删除 Pod 失败（可能已不存在）: {}, error={}", instanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public String snapshot(Long tenantId, String instanceId) {
        assertK8sAvailable();
        try {
            exec(tenantId, instanceId, "tar -cf /tmp/snapshot.tar -C /workspace .", 120);

            String[] parts = parseInstanceId(instanceId);
            try (InputStream tarStream = k8sClient.pods()
                    .inNamespace(parts[0])
                    .withName(parts[1])
                    .file("/tmp/snapshot.tar")
                    .read()) {

                String snapshotId = "snap-t" + tenantId + "-" + UUID.randomUUID();
                minioSnapshotClient.upload(snapshotId, tarStream);
                log.info("快照已保存: instanceId={}, snapshotId={}", instanceId, snapshotId);

                exec(tenantId, instanceId, "rm -f /tmp/snapshot.tar", 10);

                return snapshotId;
            }
        } catch (Exception e) {
            throw new RuntimeException("快照保存失败: instanceId=" + instanceId, e);
        }
    }

    @Override
    public String restore(Long tenantId, String snapshotId) {
        assertK8sAvailable();
        try (InputStream tarStream = minioSnapshotClient.download(snapshotId)) {
            String instanceId = create(tenantId, "python:3.11-slim", 1.0, 2048);

            String[] parts = parseInstanceId(instanceId);
            k8sClient.pods()
                    .inNamespace(parts[0])
                    .withName(parts[1])
                    .file("/tmp/restore.tar")
                    .upload(tarStream);

            exec(tenantId, instanceId, "tar -xf /tmp/restore.tar -C /workspace && rm -f /tmp/restore.tar", 60);

            log.info("快照恢复完成: snapshotId={}, newInstanceId={}", snapshotId, instanceId);
            return instanceId;
        } catch (Exception e) {
            throw new RuntimeException("快照恢复失败: snapshotId=" + snapshotId, e);
        }
    }

    @Override
    public ExecResult exec(Long tenantId, String instanceId, String command, long timeoutSec) {
        assertK8sAvailable();
        String[] parts = parseInstanceId(instanceId);

        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        String wrappedCmd = command + "; echo \"__EXIT_CODE:$?\"";

        try (ExecWatch watch = k8sClient.pods()
                .inNamespace(parts[0])
                .withName(parts[1])
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override
                    public void onOpen() { }
                    @Override
                    public void onFailure(Throwable t, Response response) {
                        latch.countDown();
                    }
                    @Override
                    public void onClose(int code, String reason) {
                        latch.countDown();
                    }
                })
                .exec("sh", "-c", wrappedCmd)) {

            boolean completed = latch.await(timeoutSec, TimeUnit.SECONDS);
            if (!completed) {
                watch.close();
                log.warn("exec 超时: instanceId={}, command={}, timeout={}s", instanceId, command, timeoutSec);
                ExecResult result = new ExecResult();
                result.stdout = stdout.toString();
                result.stderr = "TIMEOUT after " + timeoutSec + "s";
                result.exitCode = -1;
                return result;
            }
        } catch (Exception e) {
            log.error("exec 异常: instanceId={}, command={}", instanceId, command, e);
            ExecResult result = new ExecResult();
            result.stderr = e.getMessage();
            result.exitCode = -1;
            return result;
        }

        ExecResult result = new ExecResult();
        String rawStdout = stdout.toString();
        result.stderr = stderr.toString();

        int exitCode = parseExitCode(rawStdout);
        result.exitCode = exitCode;
        result.stdout = stripExitCodeLine(rawStdout);

        return result;
    }

    @Override
    public boolean probeAlive(Long tenantId, String instanceId) {
        if (k8sClient == null) {
            log.warn("K8s 客户端不可用，探活返回 false: instanceId={}", instanceId);
            return false;
        }
        try {
            String[] parts = parseInstanceId(instanceId);
            return probePodAlive(parts[0], parts[1]);
        } catch (Exception e) {
            log.debug("探活异常: instanceId={}, error={}", instanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean probeAlive(Long tenantId, String podName, String namespace) {
        if (k8sClient == null) {
            log.warn("K8s 客户端不可用，探活返回 false: podName={}, namespace={}", podName, namespace);
            return false;
        }
        try {
            return probePodAlive(namespace, podName);
        } catch (Exception e) {
            log.debug("探活异常: podName={}, namespace={}, error={}", podName, namespace, e.getMessage());
            return false;
        }
    }

    private boolean probePodAlive(String namespace, String podName) {
        Pod pod = k8sClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        return "Running".equals(pod.getStatus().getPhase());
    }

    private String resolveNamespace(Long tenantId) {
        return NS_PREFIX + (tenantId != null ? tenantId : 0) + "-standard";
    }

    private String[] parseInstanceId(String instanceId) {
        String[] parts = instanceId.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的 instanceId 格式（期望 namespace/podName）: " + instanceId);
        }
        return parts;
    }

    private String formatCpu(double cpu) {
        long millicores = Math.round(cpu * 1000);
        return millicores + "m";
    }

    private int parseExitCode(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return -1;
        }
        int idx = stdout.lastIndexOf("__EXIT_CODE:");
        if (idx < 0) {
            return -1;
        }
        String tail = stdout.substring(idx + "__EXIT_CODE:".length()).trim();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c < '0' || c > '9') {
                tail = tail.substring(0, i);
                break;
            }
        }
        try {
            return Integer.parseInt(tail.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String stripExitCodeLine(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return "";
        }
        int idx = stdout.lastIndexOf("__EXIT_CODE:");
        if (idx < 0) {
            return stdout;
        }
        int cutStart = idx;
        while (cutStart > 0 && (stdout.charAt(cutStart - 1) == '\n' || stdout.charAt(cutStart - 1) == '\r')) {
            cutStart--;
        }
        return stdout.substring(0, cutStart);
    }
}
