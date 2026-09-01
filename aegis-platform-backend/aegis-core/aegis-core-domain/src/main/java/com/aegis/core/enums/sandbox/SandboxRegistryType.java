package com.aegis.core.enums.sandbox;

import lombok.Getter;

/**
 * 镜像仓库类型枚举（对应 sbx_base_image.registry_type）。
 *
 * @author wang.zhen
 */
@Getter
public enum SandboxRegistryType {
    /** Docker Hub */
    DOCKER_HUB("Docker Hub"),
    /** Harbor 私有仓库 */
    HARBOR("Harbor");

    private final String desc;

    SandboxRegistryType(String desc) { this.desc = desc; }
}
