package com.aegis.admin.infrastructure.sandbox.spi;

import com.aegis.core.enums.sandbox.SandboxRegistryType;
import com.aegis.core.spi.IImageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 镜像仓库路由器。
 *
 * <p>根据 sbx_base_image.registry_type 字段动态路由到对应 IImageRegistry 实现。
 *
 * @author wang.zhen
 */
@Component
public class ImageRegistryRouter {

    private static final Logger log = LoggerFactory.getLogger(ImageRegistryRouter.class);

    private final Map<String, IImageRegistry> registryMap;

    public ImageRegistryRouter(List<IImageRegistry> registries) {
        this.registryMap = registries.stream()
                .collect(Collectors.toMap(IImageRegistry::getRegistryType, Function.identity()));
        log.info("ImageRegistryRouter 已加载 {} 个镜像仓库适配器: {}", registryMap.size(), registryMap.keySet());
    }

    /**
     * 按仓库类型获取适配器。
     *
     * @param registryType 仓库类型标识（DOCKER_HUB / HARBOR）
     * @return 适配器实例，未找到时回退到 Docker Hub
     */
    public IImageRegistry route(String registryType) {
        IImageRegistry adapter = registryMap.get(registryType);
        if (adapter == null) {
            log.warn("[ImageRegistry] 未找到 {} 类型的适配器，回退到 DOCKER_HUB", registryType);
            adapter = registryMap.get(SandboxRegistryType.DOCKER_HUB.name());
        }
        return adapter;
    }

    /**
     * 按枚举类型获取适配器。
     */
    public IImageRegistry route(SandboxRegistryType registryType) {
        return route(registryType.name());
    }
}