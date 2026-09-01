package com.aegis.admin.infrastructure.sandbox.spi;

import com.aegis.admin.config.infra.SandboxK8sProperties;
import com.aegis.core.enums.sandbox.SandboxRegistryType;
import com.aegis.core.spi.IImageRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Harbor 私有镜像仓库适配器。
 *
 * <p>对应 sbx_base_image.registry_type = HARBOR 的镜像。
 * 镜像引用格式：{harborHost}/{repository}:{tag}
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarborRegistryAdapter implements IImageRegistry {

    private final SandboxK8sProperties properties;

    @Override
    public String getImageRef(Long tenantId, String repository, String tag) {
        String host = properties.getRegistry().getHarbor().getHost();
        return host + "/" + repository + ":" + tag;
    }

    @Override
    public boolean imageExists(Long tenantId, String repository, String tag) {
        log.debug("[Harbor] 跳过镜像存在性校验（信任 DB 记录）: {}/{}", repository, tag);
        return true;
    }

    @Override
    public String getRegistryType() {
        return SandboxRegistryType.HARBOR.name();
    }
}