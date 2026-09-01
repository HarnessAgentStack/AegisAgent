package com.aegis.core.spi;

/**
 * 镜像仓库 SPI。
 *
 * <p>屏蔽不同镜像仓库的差异，支持 Docker Hub / Harbor 切换。
 * 默认实现位于 aegis-admin：{@code DockerHubRegistryAdapter}（docker.io）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>同步契约，保持 aegis-core 不引入响应式框架</li>
 *   <li>多租户隔离：tenantId = null 表示平台级操作</li>
 *   <li>镜像仓库类型通过 {@code sbx_base_image.registry_type} 字段匹配实现</li>
 * </ul>
 *
 * @author wang.zhen
 */
public interface IImageRegistry {

    /**
     * 获取完整镜像引用（registry/repository:tag）。
     *
     * @param tenantId   租户ID，null 表示平台级
     * @param repository 镜像仓库路径
     * @param tag        镜像标签
     * @return 完整镜像引用，如 docker.io/library/python:3.11-slim
     */
    String getImageRef(Long tenantId, String repository, String tag);

    /**
     * 验证镜像是否存在。
     *
     * @param tenantId   租户ID，null 表示平台级
     * @param repository 镜像仓库路径
     * @param tag        镜像标签
     * @return true=存在
     */
    boolean imageExists(Long tenantId, String repository, String tag);

    /**
     * 获取镜像仓库类型标识（用于 sbx_base_image.registry_type 匹配）。
     *
     * @return 类型标识，如 "DOCKER_HUB"、"HARBOR"
     */
    String getRegistryType();
}
