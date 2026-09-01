package com.aegis.admin.infrastructure.sandbox.spi;

import com.aegis.core.enums.sandbox.SandboxRegistryType;
import com.aegis.core.spi.IImageRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Docker Hub 镜像仓库适配器（默认实现）。
 *
 * <p>对应 sbx_base_image.registry_type = DOCKER_HUB 的镜像。
 * 镜像引用格式：docker.io/{repository}:{tag}
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class DockerHubRegistryAdapter implements IImageRegistry {

    private static final String DEFAULT_REGISTRY = "docker.io";

    @Override
    public String getImageRef(Long tenantId, String repository, String tag) {
        return DEFAULT_REGISTRY + "/" + repository + ":" + tag;
    }

    @Override
    public boolean imageExists(Long tenantId, String repository, String tag) {
        log.debug("[DockerHub] 跳过镜像存在性校验（信任公共仓库）: {}/{}", repository, tag);
        return true;
    }

    @Override
    public String getRegistryType() {
        return SandboxRegistryType.DOCKER_HUB.name();
    }
}