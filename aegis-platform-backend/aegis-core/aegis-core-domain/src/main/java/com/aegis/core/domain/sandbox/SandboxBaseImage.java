package com.aegis.core.domain.sandbox;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.sandbox.SandboxRegistryType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 基础镜像实体（Docker Image 注册）。
 *
 * <p>记录平台/租户可用的标准 Docker Image，K8s Pod 创建时按 registry/repository:tag 拉取。
 * 镜像构建时已完成 python 包安装 + 环境变量配置，节点本地缓存可秒级启动。
 *
 * <h3>租户隔离</h3>
 * <p>tenant_id = 0 表示系统公共镜像，> 0 表示租户私有镜像。
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sbx_base_image")
public class SandboxBaseImage extends BaseEntity {

    /** 租户ID（0=系统公共镜像，>0=租户私有镜像） */
    private Long tenantId;
    /** 镜像编码 */
    private String imageCode;
    /** 镜像名称（如 python-datascience） */
    private String imageName;
    /** 描述（包含哪些包/环境） */
    private String description;
    /** 镜像仓库类型 */
    private SandboxRegistryType registryType;
    /** 镜像仓库地址 */
    private String registry;
    /** 镜像仓库路径（如 library/python-datascience） */
    private String repository;
    /** 镜像标签 */
    private String tag;
    /** 镜像 SHA256 摘要 */
    private String digest;
    /** 镜像大小（MB） */
    private Integer imageSizeMb;
    /** 状态：ENABLED / DISABLED */
    private String status;
}
