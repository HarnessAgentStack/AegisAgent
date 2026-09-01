package com.aegis.admin.config.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 沙箱管理平面配置项。
 *
 * <p>对应 application.yml 中 {@code aegis.admin.sandbox.*} 段。
 *
 * <p>注意：本类及内部类不使用 Lombok {@code @Data}，全部手写 getter/setter，
 * 避免 Lombok annotation processor 未生效时编译失败。
 *
 * @author wang.zhen
 */
@Component
@ConfigurationProperties(prefix = "aegis.admin.sandbox")
public class SandboxK8sProperties {

    /** K8s 集群配置 */
    private K8s k8s = new K8s();

    /** Reconcile 循环调度配置 */
    private Reconcile reconcile = new Reconcile();

    /** 镜像仓库配置 */
    private Registry registry = new Registry();

    public K8s getK8s() {
        return k8s;
    }

    public void setK8s(K8s k8s) {
        this.k8s = k8s;
    }

    public Reconcile getReconcile() {
        return reconcile;
    }

    public void setReconcile(Reconcile reconcile) {
        this.reconcile = reconcile;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public static class K8s {
        /** 是否启用 K8s 集成（false 时降级为仅 DB 管理） */
        private boolean enabled = true;
        /** kubeconfig 文件路径，为空则使用默认位置 ~/.kube/config */
        private String kubeconfig = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKubeconfig() {
            return kubeconfig;
        }

        public void setKubeconfig(String kubeconfig) {
            this.kubeconfig = kubeconfig;
        }
    }

    public static class Reconcile {
        /** Reconcile 循环调度间隔（毫秒），默认 2 分钟 */
        private long intervalMs = 120000L;
        /** Pod 等待 Running 超时（毫秒），默认 120 秒 */
        private long podWaitTimeoutMs = 120000L;
        /** Pod 等待轮询间隔（毫秒），默认 3 秒 */
        private long podWaitIntervalMs = 3000L;
        /** ABNORMAL 实例自动修复重试次数，默认 3 次 */
        private int abnormalRepairRetries = 3;
        /**
         * 回收模式：true=硬回收（销毁旧 Pod + 从镜像重建 + 工作区初始化），
         * false=软回收（仅清理工作区目录，Pod 保持运行）。
         * 默认 true，确保使用过的沙箱恢复到镜像初始状态。
         */
        private boolean hardRecycle = true;
        /** 缩容最小空闲时长（分钟），默认 5 分钟。只有空闲超过阈值的 IDLE 实例才能被缩容销毁 */
        private int minIdleMinutes = 5;
        /** OCCUPIED 超时回收阈值（分钟），默认 60 分钟。长时间无心跳的 OCCUPIED 实例将被强制回收 */
        private int occupiedTimeoutMin = 60;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getPodWaitTimeoutMs() {
            return podWaitTimeoutMs;
        }

        public void setPodWaitTimeoutMs(long podWaitTimeoutMs) {
            this.podWaitTimeoutMs = podWaitTimeoutMs;
        }

        public long getPodWaitIntervalMs() {
            return podWaitIntervalMs;
        }

        public void setPodWaitIntervalMs(long podWaitIntervalMs) {
            this.podWaitIntervalMs = podWaitIntervalMs;
        }

        public int getAbnormalRepairRetries() {
            return abnormalRepairRetries;
        }

        public void setAbnormalRepairRetries(int abnormalRepairRetries) {
            this.abnormalRepairRetries = abnormalRepairRetries;
        }

        public boolean isHardRecycle() {
            return hardRecycle;
        }

        public void setHardRecycle(boolean hardRecycle) {
            this.hardRecycle = hardRecycle;
        }

        public int getMinIdleMinutes() {
            return minIdleMinutes;
        }

        public void setMinIdleMinutes(int minIdleMinutes) {
            this.minIdleMinutes = minIdleMinutes;
        }

        public int getOccupiedTimeoutMin() {
            return occupiedTimeoutMin;
        }

        public void setOccupiedTimeoutMin(int occupiedTimeoutMin) {
            this.occupiedTimeoutMin = occupiedTimeoutMin;
        }
    }

    public static class Registry {
        /** 默认镜像仓库类型：DOCKER_HUB / HARBOR */
        private String type = "DOCKER_HUB";
        /** Harbor 私有仓库配置 */
        private Harbor harbor = new Harbor();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Harbor getHarbor() {
            return harbor;
        }

        public void setHarbor(Harbor harbor) {
            this.harbor = harbor;
        }
    }

    public static class Harbor {
        private String host = "harbor.aegis.internal";
        private String username = "admin";
        private String password = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
