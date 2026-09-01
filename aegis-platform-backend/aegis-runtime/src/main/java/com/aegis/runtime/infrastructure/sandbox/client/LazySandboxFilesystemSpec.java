package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.aegis.runtime.service.sandbox.SandboxResourceLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * 懒沙箱 Spec（T1 沙箱惰性分配）。
 *
 * <p>继承 {@link AegisSandboxFilesystemSpec}，构造期将 client 替换为 {@link LazyAegisSandboxClient}
 * 并标记 {@code options.lazy=true}。框架 build 时调 {@link #createClient()} 返回懒客户端，
 * {@code LazyAegisSandboxClient#create} 识别 lazy 标志后构造 {@code instanceId=null} 占位沙箱，
 * <b>不触发 {@link AegisSandboxCoordinator#allocateSlot}</b>，实现构建期零 Pod 占用。
 *
 * <h3>与父类的关系（§4.1.3 组合委托）</h3>
 * <p>复用父类全部 builder 方法（isolationScope/tenantContext/sessionId/isolationStrategy/agentType/
 * snapshotSpec），slotKey 仍写入 options（工具侧 {@code SlotKeyParser.build} 重建一致 slotKey，
 * 供 {@code SandboxReadinessGate.awaitSandboxReady} 使用）。仅覆写 client 与 lazy 标志，
 * 回滚时 {@code configureFilesystem} 换回 {@link AegisSandboxFilesystemSpec} 即可（§13）。
 *
 * @author wang.zhen
 */
@Slf4j
public class LazySandboxFilesystemSpec extends AegisSandboxFilesystemSpec {

    /**
     * 构造懒沙箱 Spec。
     *
     * <p>调用父类构造（预创建 AegisSandboxClient），随后用 {@link LazyAegisSandboxClient} 覆盖 client，
     * 并置 {@code options.lazy=true}。
     */
    public LazySandboxFilesystemSpec(ISandboxBackend sandboxBackend,
                                      AegisSandboxCoordinator coordinator,
                                      MinioSnapshotClient minioSnapshotClient,
                                      SandboxResourceLoader resourceLoader) {
        super(sandboxBackend, coordinator, minioSnapshotClient, resourceLoader);
        LazyAegisSandboxClient lazyClient = new LazyAegisSandboxClient(
                sandboxBackend, coordinator, minioSnapshotClient, resourceLoader);
        this.client(lazyClient);
        getClientOptions().setLazy(true);
        log.debug("[sandbox-lazy] LazySandboxFilesystemSpec 初始化: client=LazyAegisSandboxClient, lazy=true");
    }
}
