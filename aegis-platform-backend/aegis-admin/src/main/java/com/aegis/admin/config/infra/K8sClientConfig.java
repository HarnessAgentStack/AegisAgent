package com.aegis.admin.config.infra;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Kubernetes Client 配置。
 *
 * <p>优先级：
 * <ol>
 *   <li>显式 kubeconfig 路径（{@code aegis.admin.sandbox.k8s.kubeconfig}）</li>
 *   <li>默认位置 ~/.kube/config（Docker Desktop K8s 启用后会自动生成）</li>
 *   <li>fabric8 自动发现（in-cluster / env vars / default）</li>
 * </ol>
 *
 * <p>未启用 K8s 或连接失败时返回 null Bean，调用方需通过 {@code K8sClusterService} 的状态判断降级处理。
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class K8sClientConfig {

    private final SandboxK8sProperties properties;
    private KubernetesClient client;

    @Bean
    public KubernetesClient kubernetesClient() {
        if (!properties.getK8s().isEnabled()) {
            log.warn("K8s 集成未启用（aegis.admin.sandbox.k8s.enabled=false），沙箱管理仅 DB 模式");
            return null;
        }

        try {
            String kubeconfig = properties.getK8s().getKubeconfig();
            // 解析 kubeconfig 路径（显式 > 默认 ~/.kube/config）
            String resolvedPath = null;
            if (kubeconfig != null && !kubeconfig.isBlank()) {
                resolvedPath = kubeconfig;
                log.info("K8s kubeconfig 使用显式路径: {}", resolvedPath);
            } else {
                Path defaultKubeconfig = Paths.get(System.getProperty("user.home"), ".kube", "config");
                if (Files.exists(defaultKubeconfig)) {
                    resolvedPath = defaultKubeconfig.toString();
                    log.info("K8s kubeconfig 使用默认位置: {}", resolvedPath);
                } else {
                    log.warn("未找到 kubeconfig 文件，使用 fabric8 自动发现模式");
                }
            }

            // fabric8 6.x 推荐使用 KubernetesClientBuilder（DefaultKubernetesClient 已废弃）
            // 注意：Config.autoConfigure(null) 仅从 KUBECONFIG 环境变量或默认位置加载，
            //       不会读取 System.setProperty("kubeconfig", path)。显式路径需用 Config.fromKubeconfig()。
            Config config;
            if (resolvedPath != null) {
                String kubeconfigContent = Files.readString(Path.of(resolvedPath));
                config = Config.fromKubeconfig(kubeconfigContent);
                log.info("K8s kubeconfig 已加载: {}", resolvedPath);
            } else {
                config = Config.autoConfigure(null);
            }
            config.setRequestTimeout(10000);
            config.setConnectionTimeout(10000);

            client = new KubernetesClientBuilder().withConfig(config).build();

            // 验证连通性
            String version = client.getVersion().getGitVersion();
            log.info("K8s 集群连接成功，版本: {}", version);

            return client;
        } catch (Throwable e) {
            // 捕获 Throwable 以处理 NoClassDefFoundError 等类加载异常，优雅降级
            log.error("K8s 集群连接失败，沙箱管理降级为仅 DB 模式: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
                log.info("K8s Client 已关闭");
            } catch (Exception e) {
                log.warn("K8s Client 关闭异常: {}", e.getMessage());
            }
        }
    }
}
