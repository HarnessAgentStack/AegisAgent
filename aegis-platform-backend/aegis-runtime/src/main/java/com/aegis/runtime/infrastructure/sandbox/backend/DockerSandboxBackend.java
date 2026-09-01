package com.aegis.runtime.infrastructure.sandbox.backend;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.config.RuntimeProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Docker 沙箱后端实现。
 *
 * <p>基于 docker-java 库实现 {@link ISandboxBackend} 协议，
 * 通过 Docker 容器提供代码执行沙箱环境。
 *
 * <h3>激活条件</h3>
 * <p>配置 {@code aegis.sandbox.backend=docker} 时激活。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aegis.runtime.sandbox.backend", havingValue = "docker")
public class DockerSandboxBackend implements ISandboxBackend {

    private final DockerClient dockerClient;
    private final RuntimeProperties props;

    /** Docker 网络模式，默认 none（隔离），可配置为 bridge（允许联网） */
    private final String networkMode;

    public DockerSandboxBackend(RuntimeProperties props) {
        this.props = props;
        // 网络模式从配置读取，默认 none（安全隔离）
        String configuredMode = System.getProperty("aegis.sandbox.docker.network-mode",
                System.getenv().getOrDefault("AEGIS_SANDBOX_NETWORK_MODE", "none"));
        this.networkMode = configuredMode;
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(props.getSandbox().getDocker().getHost())
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
        log.info("DockerSandboxBackend initialized, host={}, networkMode={}", config.getDockerHost(), networkMode);
    }

    @Override
    public String create(Long tenantId, String image, double cpu, int memoryMb) {
        try {
            String name = "aegis-sbx-" + tenantId + "-" + UUID.randomUUID().toString().substring(0, 8);
            List<Bind> binds = new ArrayList<>();
            List<String> volumeConfigs = props.getSandbox().getDocker().getVolumes();
            if (volumeConfigs != null) {
                for (String vol : volumeConfigs) {
                    String[] parts = vol.split(":", 2);
                    if (parts.length == 2) {
                        binds.add(new Bind(parts[0].trim(), new Volume(parts[1].trim())));
                    }
                }
            }

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode(networkMode)
                    .withMemory((long) memoryMb * 1024 * 1024)
                    .withCpuCount((long) Math.max(cpu, 1));
            if (!binds.isEmpty()) {
                hostConfig.withBinds(binds);
            }

            CreateContainerResponse resp = dockerClient.createContainerCmd(image)
                    .withName(name)
                    .withHostConfig(hostConfig)
                    .withVolumes(
                            new Volume("/workspace/input"),
                            new Volume("/workspace/output"),
                            new Volume("/workspace/scripts"),
                            new Volume("/workspace/temp"))
                    .withLabels(Map.of("tenant", String.valueOf(tenantId), "app", "aegis-sandbox"))
                    .exec();
            dockerClient.startContainerCmd(resp.getId()).exec();
            log.info("Docker container created: id={}, name={}, tenantId={}, cpu={}, memory={}MB",
                    resp.getId(), name, tenantId, cpu, memoryMb);
            return resp.getId();
        } catch (Exception e) {
            log.error("Failed to create docker container: tenantId={}, error={}", tenantId, e.getMessage());
            throw new RuntimeException("沙箱创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean destroy(Long tenantId, String instanceId) {
        try {
            // P0-07: 销毁容器前清理其产生的快照镜像，防止镜像泄漏
            cleanupSnapshotImages(instanceId);

            dockerClient.removeContainerCmd(instanceId).withForce(true).exec();
            log.info("Docker container destroyed: id={}, tenantId={}", instanceId, tenantId);
            return true;
        } catch (Exception e) {
            log.error("Failed to destroy docker container: id={}, error={}", instanceId, e.getMessage());
            return false;
        }
    }

    /**
     * P0-07: 清理容器产生的快照镜像。
     *
     * <p>snapshot() 方法通过 docker commit 创建的镜像（tag = aegis-snapshot-{instanceId}）
     * 不会被 Docker 自动回收，需在 destroy 时显式删除，否则会导致磁盘镜像泄漏。
     *
     * @param instanceId 容器实例 ID
     */
    private void cleanupSnapshotImages(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        // P1 SBX-11 修复：使用完整 instanceId 作为 tag，避免前 8 位碰撞导致误删/漏删
        String snapshotTag = "aegis-snapshot-" + instanceId;
        try {
            // 尝试删除快照镜像（可能不存在，忽略异常）
            dockerClient.removeImageCmd(snapshotTag).withForce(true).exec();
            log.info("P0-07: 快照镜像已清理: instanceId={}, tag={}", instanceId, snapshotTag);
        } catch (Exception e) {
            // 镜像不存在是正常情况（容器未做过快照），仅 debug
            log.debug("P0-07: 快照镜像清理跳过（可能不存在）: tag={}, error={}", snapshotTag, e.getMessage());
        }
    }

    @Override
    public String snapshot(Long tenantId, String instanceId) {
        try {
            // P1 SBX-11 修复：使用完整 instanceId 作为 tag，避免前 8 位碰撞
            String tag = "aegis-snapshot-" + instanceId;
            dockerClient.commitCmd(instanceId).withRepository(tag).exec();
            log.info("Docker snapshot created: instanceId={}, tag={}", instanceId, tag);
            return tag;
        } catch (Exception e) {
            log.error("Failed to snapshot docker container: id={}, error={}", instanceId, e.getMessage());
            throw new RuntimeException("快照创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String restore(Long tenantId, String snapshotId) {
        try {
            String name = "aegis-sbx-" + tenantId + "-" + UUID.randomUUID().toString().substring(0, 8);

            // P1-11: 复用 create 的资源限制与 Volume bind 逻辑
            List<Bind> binds = new ArrayList<>();
            List<String> volumeConfigs = props.getSandbox().getDocker().getVolumes();
            if (volumeConfigs != null) {
                for (String vol : volumeConfigs) {
                    String[] parts = vol.split(":", 2);
                    if (parts.length == 2) {
                        binds.add(new Bind(parts[0].trim(), new Volume(parts[1].trim())));
                    }
                }
            }

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode(networkMode)
                    .withMemory(2048L * 1024 * 1024)
                    .withCpuCount(1L);
            if (!binds.isEmpty()) {
                hostConfig.withBinds(binds);
            }

            CreateContainerResponse resp = dockerClient.createContainerCmd(snapshotId)
                    .withName(name)
                    .withHostConfig(hostConfig)
                    .withVolumes(
                            new Volume("/workspace/input"),
                            new Volume("/workspace/output"),
                            new Volume("/workspace/scripts"),
                            new Volume("/workspace/temp"))
                    .withLabels(Map.of("tenant", String.valueOf(tenantId), "app", "aegis-sandbox"))
                    .exec();
            dockerClient.startContainerCmd(resp.getId()).exec();
            log.info("Docker container restored from snapshot: snapshotId={}, newId={}", snapshotId, resp.getId());
            return resp.getId();
        } catch (Exception e) {
            log.error("Failed to restore docker container: snapshotId={}, error={}", snapshotId, e.getMessage());
            throw new RuntimeException("快照恢复失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecResult exec(Long tenantId, String instanceId, String command, long timeoutSec) {
        ExecResult result = new ExecResult();
        try {
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(instanceId)
                    .withCmd("/bin/sh", "-c", command)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            // 收集 stdout 和 stderr
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            var callback = dockerClient.execStartCmd(exec.getId())
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT
                                    || frame.getStreamType() == com.github.dockerjava.api.model.StreamType.RAW) {
                                stdout.append(new String(frame.getPayload()));
                            } else if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
                                stderr.append(new String(frame.getPayload()));
                            }
                        }
                    });
            // P1 SBX-09 修复：检查 awaitCompletion 返回值，超时则中断 exec 会话并标记失败
            boolean completed = callback.awaitCompletion(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                // 超时，尝试调用 onComplete 关闭 exec 会话释放资源
                try {
                    callback.onComplete();
                } catch (Exception e) {
                    log.warn("P1 SBX-09: Docker exec 超时后 onComplete 回调失败: instanceId={}, error={}",
                            instanceId, e.getMessage());
                }
                result.exitCode = -1;
                result.stdout = stdout.toString();
                result.stderr = "exec timeout after " + timeoutSec + "s";
                log.warn("P1 SBX-09: Docker exec 超时已中断: instanceId={}, timeout={}s, command={}",
                        instanceId, timeoutSec,
                        command.length() > 100 ? command.substring(0, 100) + "..." : command);
                return result;
            }

            // 查询退出码
            var inspect = dockerClient.inspectExecCmd(exec.getId()).exec();
            // P1 SBX-10 修复：exitCodeLong 为 null（超时/未结束）时不能当作 0（成功），设为 -1
            Long exitCodeLong = inspect.getExitCodeLong();
            if (exitCodeLong == null) {
                result.exitCode = -1;
                log.warn("P1 SBX-10: Docker exec exitCode 为 null，视为失败: instanceId={}", instanceId);
            } else {
                result.exitCode = exitCodeLong.intValue();
            }
            result.stdout = stdout.toString();
            result.stderr = stderr.toString();

            // 输出截断 1MB
            if (result.stdout.length() > 1024 * 1024) {
                result.stdout = result.stdout.substring(0, 1024 * 1024) + "\n... (truncated)";
            }

            log.info("Docker exec completed: instanceId={}, command={}, exitCode={}",
                    instanceId, command.length() > 100 ? command.substring(0, 100) + "..." : command, result.exitCode);
        } catch (Exception e) {
            result.exitCode = -1;
            result.stderr = e.getMessage();
            log.error("Docker exec failed: instanceId={}, error={}", instanceId, e.getMessage());
        }
        return result;
    }
}