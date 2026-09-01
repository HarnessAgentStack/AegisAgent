package com.aegis.runtime.integration.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runtime K8s 客户端配置。
 *
 * <p>当 {@code aegis.runtime.sandbox.backend=k8s} 时激活，加载 kubeconfig 连接 K8s 集群。
 * 连接失败时返回 null Bean，{@link com.aegis.core.spi.ISandboxBackend} 的 K8s 实现需判断降级。
 *
 * <p>优先级：
 * <ol>
 *   <li>显式 kubeconfig 路径（{@code aegis.runtime.sandbox.k8s.kubeconfig}）</li>
 *   <li>默认位置 ~/.kube/config</li>
 *   <li>fabric8 自动发现</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "aegis.runtime.sandbox.backend", havingValue = "k8s")
public class RuntimeK8sConfig {

    @Value("${aegis.runtime.sandbox.k8s.kubeconfig:}")
    private String kubeconfigPath;

    private KubernetesClient client;

    @Bean
    public KubernetesClient runtimeKubernetesClient() {
        try {
            Config config;
            String resolvedPath = resolveKubeconfigPath();
            if (resolvedPath != null) {
                String content = Files.readString(Path.of(resolvedPath));
                config = Config.fromKubeconfig(content);
                log.info("Runtime K8s kubeconfig 已加载: {}", resolvedPath);
            } else {
                config = Config.autoConfigure(null);
            }
            config.setRequestTimeout(10000);
            config.setConnectionTimeout(10000);

            client = new KubernetesClientBuilder().withConfig(config).build();
            String version = client.getVersion().getGitVersion();
            log.info("Runtime K8s 集群连接成功，版本: {}", version);
            return client;
        } catch (Throwable e) {
            log.error("Runtime K8s 连接失败，K8s 沙箱后端不可用: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
            log.info("Runtime K8s 客户端已关闭");
        }
    }

    private String resolveKubeconfigPath() {
        if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
            return kubeconfigPath;
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            Path defaultPath = Paths.get(home, ".kube", "config");
            if (Files.exists(defaultPath)) {
                return defaultPath.toString();
            }
        }
        return null;
    }
}
