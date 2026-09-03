package com.aegis.runtime.integration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import com.aegis.runtime.integration.pool.AgentPoolManager;

/**
 * 运行时配置属性。
 *
 * <p>承载 aegis-runtime 运行期的可调参数：Layer 1 池化参数、沙箱池配置、沙箱后端配置、SSE 压缩阈值等。
 * 通过 {@code @ConfigurationProperties} 绑定 Nacos 配置中心，支持运行期热更新。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code agent-pool}：智能体运行时模板池参数（容量/空闲回收/预热数）</li>
 *   <li>{@code sandbox-pool}：沙箱池参数（最小/最大/扩缩容/回收超时）</li>
 *   <li>{@code sandbox}：沙箱后端参数（Docker/进程模式/镜像/回收策略）</li>
 *   <li>{@code sse-compress-threshold}：SSE 流压缩阈值（字节），超阈值启用 gzip</li>
 *   <li>{@code session-timeout-minutes}：会话空闲超时，超时回收池化资源</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>池化参数变更通过 {@code ConfigChangedEvent} 热生效，无需重启</li>
 *   <li>压缩阈值权衡 CPU 与带宽：小消息不压缩，大消息压缩降低带宽</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AgentPoolManager
 * @see com.aegis.runtime.infrastructure.startup.SandboxHealthMonitor
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.runtime")
public class RuntimeProperties {

    /** 智能体运行时模板池配置 */
    private AgentPool agentPool = new AgentPool();

    /** 沙箱池配置 */
    private SandboxPool sandboxPool = new SandboxPool();

    /** 沙箱后端配置（Docker/进程模式/镜像/回收策略） */
    private Sandbox sandbox = new Sandbox();

    /** SSE 流压缩阈值（字节），默认 1KB */
    private int sseCompressThreshold = 1024;

    /** 会话空闲超时（分钟），超时回收池化资源 */
    private int sessionTimeoutMinutes = 30;

    /** Layer 1 智能体运行时模板池配置。 */
    @Data
    public static class AgentPool {
        /** 每租户最大模板缓存数 */
        private int maxPerTenant = 100;
        /** 空闲回收时长（分钟） */
        private int idleEvictMinutes = 60;
        /** 启动预热模板数（热门智能体） */
        private int warmupCount = 10;
    }

    /** Layer 1 沙箱池配置。 */
    @Data
    public static class SandboxPool {
        /** 每租户最小保持沙箱数 */
        private int minPerTenant = 1;
        /** 每租户最大沙箱数 */
        private int maxPerTenant = 20;
        /** 空闲沙箱回收超时（分钟） */
        private int idleEvictMinutes = 15;
        /** 扩容触发水位（0~1） */
        private double scaleUpThreshold = 0.8;
    }

    /**
     * 沙箱后端配置。
     *
     * <p>绑定 {@code aegis.runtime.sandbox} 前缀，覆盖沙箱后端类型、Docker 连接、
     * 池规格、回收策略等完整配置。
     */
    @Data
    public static class Sandbox {
        /** 是否启用沙箱 */
        private boolean enabled = true;
        /** 后端类型：docker | k8s | process */
        private String backend = "process";
        /**
         * 框架驱动灰度开关（周期4：沙箱工具改经 SandboxSessionHolder + SandboxManager 驱动）。
         * 默认 false：关闭即回退旧路径 AegisSandboxPoolExecutor.exec。
         */
        private FrameworkDrive frameworkDrive = new FrameworkDrive();
        /** 默认沙箱镜像 */
        private String image = "python:3.11-slim";
        /** 默认 CPU 配额（核） */
        private double cpu = 1.0;
        /** 默认内存配额（MB） */
        private int memoryMb = 512;
        /** 框架驱动灰度配置。 */
        @Data
        public static class FrameworkDrive {
            /** 是否启用框架驱动沙箱工具执行（周期4灰度开关） */
            private boolean enabled = false;
        }

        /** Docker 连接配置 */
        private Docker docker = new Docker();
        /** 池规格配置 */
        private Pool pool = new Pool();
        /** 回收策略配置 */
        private Recycle recycle = new Recycle();

        /** Docker 连接配置。 */
        @Data
        public static class Docker {
            /** Docker 主机地址 */
            private String host = "unix:///var/run/docker.sock";
            /** Docker 镜像仓库地址 */
            private String registry = "registry.aegis.local";
            /** 容器创建超时（秒） */
            private int createTimeoutSec = 60;
            /** 卷挂载列表，格式为 host_path:container_path */
            private List<String> volumes = new ArrayList<>();
        }

        /** 池规格配置。 */
        @Data
        public static class Pool {
            /** 轻量池规格 */
            private PoolSize light = new PoolSize("0.5", 256, 60);
            /** 标准池规格 */
            private PoolSize standard = new PoolSize("1", 512, 300);
            /** 重型池规格 */
            private PoolSize heavy = new PoolSize("2", 2048, 600);
        }

        /** 单池规格。 */
        @Data
        public static class PoolSize {
            /** CPU 配额（字符串，如 "0.5"、"1"、"2"） */
            private String cpu;
            /** 内存配额（MB） */
            private int memory;
            /** 执行超时（秒） */
            private int timeout;

            public PoolSize() {}

            public PoolSize(String cpu, int memory, int timeout) {
                this.cpu = cpu;
                this.memory = memory;
                this.timeout = timeout;
            }
        }

        /** 回收策略配置。 */
        @Data
        public static class Recycle {
            /** 最大复用次数，超限深度回收 */
            private int maxReuseCount = 20;
            /** 空闲超时（分钟），超时深度回收 */
            private int idleTimeoutMinutes = 30;
            /** 回收时是否清理工作空间 */
            private boolean cleanupWorkspace = true;
            /** 健康检查间隔（毫秒），也是定时回收间隔 */
            private long healthCheckIntervalMs = 300000;
        }
    }
}
