package com.aegis.admin.infrastructure.sandbox;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kubernetes 集群操作服务（基于 fabric8）。
 *
 * <p>封装 Namespace / ResourceQuota / NetworkPolicy / Pod 的管理操作，
 * 以及集群资源预检查、Pod 内命令执行等能力。
 * 当 K8s 未启用或连接失败时，所有方法安全降级（返回 false / empty），不影响 DB 操作。
 *
 * <h3>命名约定</h3>
 * <ul>
 *   <li>Namespace: aegis-sbx-t{tenantId}-{poolTypeLower}（如 aegis-sbx-t0-light）</li>
 *   <li>Pod: sbx-{poolCode}-{随机8位}（如 sbx-sys-light-a1b2c3d4）</li>
 *   <li>Label: app=sbx-sandbox, tenant={tenantId}, pool={poolCode}</li>
 * </ul>
 *
 * <h3>NetworkPolicy 语义</h3>
 * <ul>
 *   <li>ISOLATED：完全隔离，仅允许同 Namespace Pod 间通信（deny all ingress + egress except same-ns）</li>
 *   <li>RESTRICTED：限制出站，允许 DNS + 集群内部，禁止公网</li>
 *   <li>NO_EXTERNAL：禁止外网，允许集群内部所有通信</li>
 *   <li>OPEN：允许联网，不创建 NetworkPolicy（完全开放）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Service
@RequiredArgsConstructor
public class K8sClusterService {

    private static final Logger log = LoggerFactory.getLogger(K8sClusterService.class);

    private final ObjectProvider<KubernetesClient> clientProvider;

    /**
     * 获取 K8s 客户端（可能为 null，表示 K8s 不可用）。
     */
    public KubernetesClient getClient() {
        return clientProvider.getIfAvailable();
    }

    /**
     * K8s 是否可用。
     */
    public boolean isAvailable() {
        return getClient() != null;
    }

    // =========================================================================
    // 集群资源预检查
    // =========================================================================

    /**
     * 检查集群是否有足够的可分配资源。
     *
     * <p>汇总所有 Ready 节点的 allocatable 资源，与需求对比。
     * 用于池创建前的预检查，避免创建后 Pod 因资源不足无法调度。
     *
     * @param requiredCpuCores    需要 CPU 核数（如 2.0 = 2 核）
     * @param requiredMemMb       需要内存（MB）
     * @return 检查结果
     */
    public ClusterResourceCheckResult checkClusterResource(double requiredCpuCores, int requiredMemMb) {
        KubernetesClient client = getClient();
        if (client == null) {
            return ClusterResourceCheckResult.fail("K8s 集群未连接，无法创建沙箱池");
        }
        try {
            List<Node> nodes = client.nodes().list().getItems();
            double totalCpu = 0;
            long totalMemMb = 0;
            int readyNodes = 0;

            for (Node node : nodes) {
                boolean ready = node.getStatus().getConditions().stream()
                        .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                if (!ready) {
                    continue;
                }
                readyNodes++;

                Map<String, Quantity> allocatable = node.getStatus().getAllocatable();
                if (allocatable != null) {
                    totalCpu += parseCpuToCores(allocatable.get("cpu"));
                    totalMemMb += parseMemToMb(allocatable.get("memory"));
                }
            }

            if (readyNodes == 0) {
                return ClusterResourceCheckResult.fail("集群中没有 Ready 节点");
            }

            boolean cpuOk = totalCpu >= requiredCpuCores;
            boolean memOk = totalMemMb >= requiredMemMb;

            if (cpuOk && memOk) {
                return ClusterResourceCheckResult.ok(totalCpu, totalMemMb, readyNodes);
            }

            String msg = String.format("K8s 集群资源不足: 需要 CPU %.2f 核 / 内存 %d MB, 可用 CPU %.2f 核 / 内存 %d MB (%d 个 Ready 节点)",
                    requiredCpuCores, requiredMemMb, totalCpu, totalMemMb, readyNodes);
            return ClusterResourceCheckResult.fail(msg);
        } catch (Exception e) {
            log.error("[K8s] 集群资源检查失败，降级为跳过预检查（K8s 调度器将兜底资源约束）", e);
            return ClusterResourceCheckResult.skipped(e.getMessage());
        }
    }

    // =========================================================================
    // Namespace 管理
    // =========================================================================

    /**
     * 创建命名空间（幂等）。
     */
    public boolean createNamespace(String namespace) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 createNamespace: {}", namespace);
            return false;
        }
        try {
            Namespace existing = client.namespaces().withName(namespace).get();
            if (existing != null) {
                log.debug("[K8s] Namespace 已存在: {}", namespace);
                return true;
            }
            Namespace ns = new NamespaceBuilder()
                    .withNewMetadata()
                        .withName(namespace)
                        .addToLabels("app", "aegis-sbx")
                        .addToLabels("managed-by", "aegis-admin")
                        .endMetadata()
                    .build();
            client.namespaces().resource(ns).create();
            log.info("[K8s] Namespace 创建成功: {}", namespace);
            return true;
        } catch (Exception e) {
            log.error("[K8s] Namespace 创建失败 {}: {}", namespace, e.getMessage());
            return false;
        }
    }

    /**
     * 删除命名空间（幂等）。级联删除其下所有资源（Pod/Quota/NetworkPolicy）。
     */
    public boolean deleteNamespace(String namespace) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 deleteNamespace: {}", namespace);
            return false;
        }
        try {
            Namespace existing = client.namespaces().withName(namespace).get();
            if (existing == null) {
                log.debug("[K8s] Namespace 不存在，跳过删除: {}", namespace);
                return true;
            }
            client.namespaces().withName(namespace).delete();
            log.info("[K8s] Namespace 删除成功: {}", namespace);
            return true;
        } catch (Exception e) {
            log.error("[K8s] Namespace 删除失败 {}: {}", namespace, e.getMessage());
            return false;
        }
    }

    /**
     * 命名空间是否存在。
     */
    public boolean namespaceExists(String namespace) {
        KubernetesClient client = getClient();
        if (client == null) {
            return false;
        }
        try {
            return client.namespaces().withName(namespace).get() != null;
        } catch (Exception e) {
            log.error("[K8s] Namespace 查询失败 {}: {}", namespace, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // ResourceQuota 管理
    // =========================================================================

    /**
     * 创建/更新命名空间的 ResourceQuota（租户级资源配额）。
     *
     * @param namespace  命名空间
     * @param quotaName  Quota 名称
     * @param cpuLimit   CPU 上限（如 "2" 或 "4"，millicores 格式如 "2500m"）
     * @param memLimitMb 内存上限（MB）
     * @param podCount   Pod 数量上限
     */
    public boolean applyResourceQuota(String namespace, String quotaName,
                                      String cpuLimit, int memLimitMb, int podCount) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 applyResourceQuota: {}/{}", namespace, quotaName);
            return false;
        }
        try {
            ResourceQuota quota = new ResourceQuotaBuilder()
                    .withNewMetadata()
                        .withName(quotaName)
                        .withNamespace(namespace)
                        .endMetadata()
                    .withNewSpec()
                        .addToHard("limits.cpu", new Quantity(cpuLimit))
                        .addToHard("limits.memory", new Quantity(memLimitMb + "Mi"))
                        .addToHard("pods", new Quantity(String.valueOf(podCount)))
                        .endSpec()
                    .build();
            client.resourceQuotas().inNamespace(namespace).resource(quota).createOrReplace();
            log.info("[K8s] ResourceQuota 应用成功: {}/{}", namespace, quotaName);
            return true;
        } catch (Exception e) {
            log.error("[K8s] ResourceQuota 应用失败 {}/{}: {}", namespace, quotaName, e.getMessage());
            return false;
        }
    }

    /**
     * 删除 ResourceQuota。
     */
    public boolean deleteResourceQuota(String namespace, String quotaName) {
        KubernetesClient client = getClient();
        if (client == null) {
            return false;
        }
        try {
            client.resourceQuotas().inNamespace(namespace).withName(quotaName).delete();
            return true;
        } catch (Exception e) {
            log.error("[K8s] ResourceQuota 删除失败 {}/{}: {}", namespace, quotaName, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // NetworkPolicy 管理
    // =========================================================================

    /**
     * 创建/更新 NetworkPolicy（按 networkPolicy 类型配置隔离规则）。
     *
     * <p>隔离级别：
     * <ul>
     *   <li>ISOLATED: 完全隔离，deny all ingress，仅允许同 NS Pod 间 egress</li>
     *   <li>RESTRICTED: 允许 DNS 出站到 kube-system + 同 NS 通信，禁止公网</li>
     *   <li>NO_EXTERNAL: 允许集群内部所有通信，禁止外网 ingress + egress</li>
     *   <li>OPEN: 不创建 NetworkPolicy，完全开放</li>
     * </ul>
     */
    public boolean applyNetworkPolicy(String namespace, String policyName, String networkPolicyType) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 applyNetworkPolicy: {}/{}", namespace, policyName);
            return false;
        }
        // OPEN：不创建 NetworkPolicy，完全开放
        if ("OPEN".equalsIgnoreCase(networkPolicyType)) {
            deleteNetworkPolicy(namespace, policyName);
            log.info("[K8s] OPEN 模式，不创建 NetworkPolicy: {}/{}", namespace, policyName);
            return true;
        }
        try {
            io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicySpec spec = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicySpec();
            io.fabric8.kubernetes.api.model.LabelSelector podSelector = new io.fabric8.kubernetes.api.model.LabelSelector();
            podSelector.setMatchLabels(Map.of("app", "sbx-sandbox"));
            spec.setPodSelector(podSelector);

            List<NetworkPolicyEgressRule> egressRules = new java.util.ArrayList<>();

            if ("ISOLATED".equalsIgnoreCase(networkPolicyType)) {
                spec.setIngress(List.of(new NetworkPolicyIngressRule()));
                NetworkPolicyEgressRule sameNsEgress = new NetworkPolicyEgressRule();
                io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer sameNsPeer = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer();
                sameNsPeer.setPodSelector(new io.fabric8.kubernetes.api.model.LabelSelector());
                sameNsEgress.setTo(List.of(sameNsPeer));
                egressRules.add(sameNsEgress);
            } else if ("RESTRICTED".equalsIgnoreCase(networkPolicyType)) {
                NetworkPolicyEgressRule dnsEgress = new NetworkPolicyEgressRule();
                io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer dnsPeer = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer();
                io.fabric8.kubernetes.api.model.LabelSelector nsSelector = new io.fabric8.kubernetes.api.model.LabelSelector();
                nsSelector.setMatchLabels(Map.of("kubernetes.io/metadata.name", "kube-system"));
                dnsPeer.setNamespaceSelector(nsSelector);
                io.fabric8.kubernetes.api.model.LabelSelector podSel = new io.fabric8.kubernetes.api.model.LabelSelector();
                podSel.setMatchLabels(Map.of("k8s-app", "kube-dns"));
                dnsPeer.setPodSelector(podSel);
                dnsEgress.setTo(List.of(dnsPeer));
                io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort dnsPort = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort();
                dnsPort.setPort(new io.fabric8.kubernetes.api.model.IntOrString(53));
                dnsPort.setProtocol("UDP");
                dnsEgress.setPorts(List.of(dnsPort));
                egressRules.add(dnsEgress);

                NetworkPolicyEgressRule sameNsEgress = new NetworkPolicyEgressRule();
                io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer sameNsPeer = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer();
                sameNsPeer.setPodSelector(new io.fabric8.kubernetes.api.model.LabelSelector());
                sameNsEgress.setTo(List.of(sameNsPeer));
                egressRules.add(sameNsEgress);
            } else if ("NO_EXTERNAL".equalsIgnoreCase(networkPolicyType)) {
                for (String cidr : List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) {
                    NetworkPolicyEgressRule internalEgress = new NetworkPolicyEgressRule();
                    io.fabric8.kubernetes.api.model.networking.v1.IPBlock ipBlock = new io.fabric8.kubernetes.api.model.networking.v1.IPBlock();
                    ipBlock.setCidr(cidr);
                    io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer peer = new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer();
                    peer.setIpBlock(ipBlock);
                    internalEgress.setTo(List.of(peer));
                    egressRules.add(internalEgress);
                }
            }
            spec.setEgress(egressRules);

            NetworkPolicy policy = new NetworkPolicy();
            io.fabric8.kubernetes.api.model.ObjectMeta meta = new io.fabric8.kubernetes.api.model.ObjectMeta();
            meta.setName(policyName);
            meta.setNamespace(namespace);
            policy.setMetadata(meta);
            policy.setSpec(spec);

            client.network().networkPolicies().inNamespace(namespace).resource(policy).createOrReplace();
            log.info("[K8s] NetworkPolicy 应用成功: {}/{} ({})", namespace, policyName, networkPolicyType);
            return true;
        } catch (Exception e) {
            log.error("[K8s] NetworkPolicy 应用失败 {}/{}: {}", namespace, policyName, e.getMessage());
            return false;
        }
    }

    /**
     * 删除 NetworkPolicy。
     */
    public boolean deleteNetworkPolicy(String namespace, String policyName) {
        KubernetesClient client = getClient();
        if (client == null) {
            return false;
        }
        try {
            client.network().networkPolicies().inNamespace(namespace).withName(policyName).delete();
            return true;
        } catch (Exception e) {
            log.error("[K8s] NetworkPolicy 删除失败 {}/{}: {}", namespace, policyName, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Pod 管理
    // =========================================================================

    /**
     * Pod 创建结果。
     *
     * <p>QUOTA_EXCEEDED 用于上层批量创建流程（预热/修复/常驻保障）快速终止循环：
     * ResourceQuota 已满时后续创建必然失败，继续重试只会刷日志（重试风暴）。
     */
    public enum PodCreateResult {
        /** 创建成功 */
        CREATED,
        /** ResourceQuota 已满（K8s 403 exceeded quota），需先释放资源 */
        QUOTA_EXCEEDED,
        /** 其他失败（K8s 不可用、镜像错误、网络异常等） */
        FAILED
    }

    /**
     * 创建沙箱 Pod。
     *
     * @param namespace   命名空间
     * @param podName     Pod 名
     * @param imageRef    完整镜像引用（registry/repository:tag）
     * @param cpuLimit    CPU 上限（如 "0.5"）
     * @param memLimitMb  内存上限（MB）
     * @param labels      额外 Label
     * @return 创建结果（见 {@link PodCreateResult}）
     */
    public PodCreateResult createSandboxPod(String namespace, String podName, String imageRef,
                                            String cpuLimit, int memLimitMb, Map<String, String> labels) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 createSandboxPod: {}/{}", namespace, podName);
            return PodCreateResult.FAILED;
        }
        try {
            ResourceRequirements resources = new ResourceRequirementsBuilder()
                    .addToLimits("cpu", new Quantity(cpuLimit))
                    .addToLimits("memory", new Quantity(memLimitMb + "Mi"))
                    .addToRequests("cpu", new Quantity(parseCpuRequest(cpuLimit)))
                    .addToRequests("memory", new Quantity((memLimitMb / 2) + "Mi"))
                    .build();

            Container container = new ContainerBuilder()
                    .withName("sandbox")
                    .withImage(imageRef)
                    .withImagePullPolicy("IfNotPresent")
                    .withCommand("sleep", "infinity")
                    .withResources(resources)
                    .build();

            Pod pod = new PodBuilder()
                    .withNewMetadata()
                        .withName(podName)
                        .withNamespace(namespace)
                        .addToLabels("app", "sbx-sandbox")
                        .addToLabels(labels)
                        .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Always")
                        .withContainers(container)
                        .endSpec()
                    .build();

            client.pods().inNamespace(namespace).resource(pod).create();
            log.info("[K8s] Pod 创建成功: {}/{}", namespace, podName);
            return PodCreateResult.CREATED;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "";
            log.error("[K8s] Pod 创建失败 {}/{}: {}", namespace, podName, message);
            if (message.toLowerCase().contains("exceeded quota")) {
                return PodCreateResult.QUOTA_EXCEEDED;
            }
            return PodCreateResult.FAILED;
        }
    }

    /**
     * 删除 Pod（幂等）。
     */
    public boolean deletePod(String namespace, String podName) {
        KubernetesClient client = getClient();
        if (client == null) {
            return false;
        }
        try {
            client.pods().inNamespace(namespace).withName(podName).delete();
            log.info("[K8s] Pod 删除成功: {}/{}", namespace, podName);
            return true;
        } catch (Exception e) {
            log.error("[K8s] Pod 删除失败 {}/{}: {}", namespace, podName, e.getMessage());
            return false;
        }
    }

    /**
     * 查询 Pod 状态。
     *
     * @return phase: Pending/Running/Succeeded/Failed/Unknown，K8s 不可用时返回 "UNKNOWN"
     */
    public String getPodPhase(String namespace, String podName) {
        KubernetesClient client = getClient();
        if (client == null) {
            return "UNKNOWN";
        }
        try {
            Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                return "NOT_FOUND";
            }
            return Optional.ofNullable(pod.getStatus()).map(s -> s.getPhase()).orElse("Unknown");
        } catch (Exception e) {
            log.error("[K8s] Pod 状态查询失败 {}/{}: {}", namespace, podName, e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * 等待 Pod 进入 Running 状态（轮询，超时返回 false）。
     *
     * @param namespace 命名空间
     * @param podName   Pod 名
     * @param timeoutMs 超时时间（毫秒）
     * @param intervalMs 轮询间隔（毫秒）
     * @return true=Pod 已 Running
     */
    public boolean waitForPodRunning(String namespace, String podName, long timeoutMs, long intervalMs) {
        KubernetesClient client = getClient();
        if (client == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String phase = getPodPhase(namespace, podName);
            if ("Running".equals(phase)) {
                return true;
            }
            if ("Failed".equals(phase) || "NOT_FOUND".equals(phase)) {
                log.warn("[K8s] Pod 进入异常状态: {}/{} phase={}", namespace, podName, phase);
                return false;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("[K8s] Pod 等待超时: {}/{} timeoutMs={}", namespace, podName, timeoutMs);
        return false;
    }

    /**
     * 在 Pod 内执行命令（用于工作区重初始化）。
     *
     * @param namespace 命名空间
     * @param podName   Pod 名
     * @param command   要执行的命令
     * @return 命令输出，失败时返回 null
     */
    public String execInPod(String namespace, String podName, String command) {
        KubernetesClient client = getClient();
        if (client == null) {
            log.warn("[K8s] 不可用，跳过 execInPod: {}/{}", namespace, podName);
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(output)
                    .exec("sh", "-c", command)) {
            }
            String result = output.toString().trim();
            log.debug("[K8s] exec 完成: {}/{} cmd='{}' output='{}'", namespace, podName, command, result);
            return result;
        } catch (Exception e) {
            log.error("[K8s] exec 失败: {}/{} cmd='{}': {}", namespace, podName, command, e.getMessage());
            return null;
        }
    }

    /**
     * 探活 Pod（检查是否可达）。
     *
     * @param namespace 命名空间
     * @param podName   Pod 名
     * @return true=Pod 可达（Running 且 exec 成功）
     */
    public boolean probePod(String namespace, String podName) {
        String phase = getPodPhase(namespace, podName);
        if (!"Running".equals(phase)) {
            return false;
        }
        String result = execInPod(namespace, podName, "echo ok");
        return "ok".equals(result);
    }

    /**
     * 列出 Namespace 下所有沙箱 Pod。
     */
    public List<Pod> listSandboxPods(String namespace) {
        KubernetesClient client = getClient();
        if (client == null) {
            return List.of();
        }
        try {
            return client.pods().inNamespace(namespace).withLabel("app", "sbx-sandbox").list().getItems();
        } catch (Exception e) {
            log.error("[K8s] Pod 列表查询失败 {}: {}", namespace, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * CPU 请求值 = 限制值的 50%（保守策略，避免资源浪费）。
     */
    private String parseCpuRequest(String cpuLimit) {
        try {
            double limit = Double.parseDouble(cpuLimit);
            double request = Math.max(limit * 0.5, 0.1);
            return String.valueOf(request);
        } catch (NumberFormatException e) {
            return "0.1";
        }
    }

    /**
     * 解析 K8s Quantity CPU 为核数（如 "2000m" → 2.0, "2" → 2.0）。
     */
    private double parseCpuToCores(Quantity quantity) {
        if (quantity == null) {
            return 0;
        }
        String amount = quantity.getAmount();
        String format = quantity.getFormat();
        if (amount == null || amount.isEmpty()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(amount);
            if ("m".equals(format)) {
                return value / 1000;
            }
            return value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析 K8s Quantity Memory 为 MB（如 "4Gi" → 4096, "512Mi" → 512）。
     */
    private long parseMemToMb(Quantity quantity) {
        if (quantity == null) {
            return 0;
        }
        String amount = quantity.getAmount();
        String format = quantity.getFormat();
        if (amount == null || amount.isEmpty()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(amount);
            if (format == null || format.isEmpty()) {
                return (long) (value / (1024 * 1024));
            }
            switch (format) {
                case "Ki": return (long) (value * 1024 / (1024 * 1024) * 1024) / 1024;
                case "Mi": return (long) value;
                case "Gi": return (long) (value * 1024);
                case "Ti": return (long) (value * 1024 * 1024);
                case "K": return (long) (value * 1000 / (1024 * 1024));
                case "M": return (long) (value * 1000 * 1000 / (1024 * 1024));
                case "G": return (long) (value * 1000 * 1000 * 1000 / (1024 * 1024));
                default: return (long) (value / (1024 * 1024));
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // =========================================================================
    // 集群资源检查结果
    // =========================================================================

    /**
     * 集群资源检查结果。
     */
    public static class ClusterResourceCheckResult {
        private final boolean sufficient;
        private final String message;
        private final double availableCpu;
        private final long availableMemMb;
        private final int readyNodes;

        private ClusterResourceCheckResult(boolean sufficient, String message,
                                           double availableCpu, long availableMemMb, int readyNodes) {
            this.sufficient = sufficient;
            this.message = message;
            this.availableCpu = availableCpu;
            this.availableMemMb = availableMemMb;
            this.readyNodes = readyNodes;
        }

        public static ClusterResourceCheckResult ok(double cpu, long memMb, int nodes) {
            return new ClusterResourceCheckResult(true, "集群资源充足", cpu, memMb, nodes);
        }

        public static ClusterResourceCheckResult fail(String message) {
            return new ClusterResourceCheckResult(false, message, 0, 0, 0);
        }

        /**
         * 资源检查跳过（API 异常时降级，允许继续建池，由 K8s 调度器兜底）。
         */
        public static ClusterResourceCheckResult skipped(String reason) {
            return new ClusterResourceCheckResult(true, "集群资源检查跳过: " + reason, 0, 0, 0);
        }

        public boolean isSufficient() { return sufficient; }
        public String getMessage() { return message; }
        public double getAvailableCpu() { return availableCpu; }
        public long getAvailableMemMb() { return availableMemMb; }
        public int getReadyNodes() { return readyNodes; }
    }
}