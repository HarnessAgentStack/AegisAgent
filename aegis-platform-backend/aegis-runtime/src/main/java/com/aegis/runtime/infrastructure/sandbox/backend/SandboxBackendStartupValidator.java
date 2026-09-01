package com.aegis.runtime.infrastructure.sandbox.backend;

import com.aegis.core.spi.ISandboxBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 沙箱后端启动校验器（P0-3）。
 *
 * <p>{@link ProcessSandboxBackend} 的 {@code matchIfMissing} 改为 false 后，未配置
 * {@code aegis.runtime.sandbox.backend} 时不再有默认后端隐式激活。本校验器在应用就绪时
 * 显式断言后端已正确配置，给出清晰的 fail-fast 提示（三选一：k8s / docker / process），
 * 避免依赖隐式 {@code NoSuchBeanDefinitionException} 导致的晦涩启动失败。
 *
 * <p>校验失败时抛出 {@link IllegalStateException} 阻断启动——与"沙箱不可用即 fail-closed"
 * 语义一致：宁可启动失败，也不在无沙箱状态下静默放行 execute。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SandboxBackendStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Set<String> VALID_BACKENDS = Set.of("k8s", "docker", "process");

    private final ObjectProvider<ISandboxBackend> sandboxBackendProvider;
    private final String configuredBackend;

    public SandboxBackendStartupValidator(ObjectProvider<ISandboxBackend> sandboxBackendProvider,
                                          @Value("${aegis.runtime.sandbox.backend:}") String configuredBackend) {
        this.sandboxBackendProvider = sandboxBackendProvider;
        this.configuredBackend = configuredBackend == null ? "" : configuredBackend.trim();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (configuredBackend.isEmpty()) {
            throw new IllegalStateException(
                    "P0-3 启动校验失败：未配置 aegis.runtime.sandbox.backend。"
                    + "请显式声明三选一（k8s / docker / process）；未配置时 execute 工具 fail-closed 拒绝执行，"
                    + "不再隐式回退到 process 宿主执行。");
        }
        if (!VALID_BACKENDS.contains(configuredBackend.toLowerCase())) {
            throw new IllegalStateException(
                    "P0-3 启动校验失败：aegis.runtime.sandbox.backend=" + configuredBackend
                    + " 非法，仅支持 k8s / docker / process。");
        }
        ISandboxBackend backend = sandboxBackendProvider.getIfAvailable();
        if (backend == null) {
            throw new IllegalStateException(
                    "P0-3 启动校验失败：声明 backend=" + configuredBackend
                    + " 但未装配任何 ISandboxBackend Bean（对应后端实现类未激活/依赖缺失）。");
        }
        log.info("P0-3 沙箱后端启动校验通过: backend={}, impl={}",
                configuredBackend, backend.getClass().getSimpleName());
    }
}
