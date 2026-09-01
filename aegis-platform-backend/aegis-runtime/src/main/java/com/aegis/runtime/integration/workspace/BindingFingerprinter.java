package com.aegis.runtime.integration.workspace;

import com.aegis.core.domain.agent.AgentBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/**
 * 绑定指纹统一计算（InstanceManager 与 WorkspaceMaterializer 共享）。
 *
 * <p>按 resourceType:resourceId:resourceVersion 排序拼接后 SHA-256，空绑定返回 "EMPTY"。
 * 绑定变更则指纹变化，用于实例池懒刷新与工作区重物化触发判定。
 */
public final class BindingFingerprinter {

    private BindingFingerprinter() {
    }

    /**
     * 计算绑定列表的指纹。
     *
     * @param bindings agent_binding 条目列表（可为 null 或空）
     * @return 64 位十六进制 SHA-256 摘要；空绑定时返回 "EMPTY"
     */
    public static String fingerprint(List<AgentBinding> bindings) {
        List<String> parts = new ArrayList<>();
        if (bindings != null && !bindings.isEmpty()) {
            for (AgentBinding b : bindings) {
                String ver = b.getResourceVersion() != null ? String.valueOf(b.getResourceVersion()) : "0";
                parts.add(b.getResourceType() + ":" + b.getResourceId() + ":" + ver);
            }
        }
        if (parts.isEmpty()) {
            return "EMPTY";
        }
        parts.sort(String::compareTo);
        return sha256(String.join("|", parts));
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "EMPTY";
        }
    }
}
