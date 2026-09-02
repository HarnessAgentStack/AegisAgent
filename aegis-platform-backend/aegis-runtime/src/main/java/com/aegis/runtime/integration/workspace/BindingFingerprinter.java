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
 * <p>按 agentVersion + resourceType:resourceId:resourceVersion 排序拼接后 SHA-256，
 * 空输入返回 "EMPTY"。绑定或版本变更则指纹变化，用于实例池懒刷新与工作区重物化触发判定。
 *
 * <h3>P1-8：版本参与哈希</h3>
 * <p>指纹输入纳入 agentVersion——同一份绑定集合跨版本视为不同指纹，
 * 与池键含版本（P1-2）共同保证「老会话钉住版本」与「新会话最新版本」
 * 的实例/工具集不会因绑定巧合相同而误判为一致。
 *
 * <h3>UNIVERSAL 订阅指纹盲区修复</h3>
 * <p>UNIVERSAL 智能体的模板绑定通常为空，若指纹仅由 agentVersion + bindings 构成，
 * 则用户订阅/自建资源变化不会改变指纹——池命中后永远复用旧实例，
 * 新订阅的 MCP/Skill 工具从未进入 Toolkit（即「订阅的 MCP 不可用」根因）。
 * 修复：三参重载追加 {@code dynamicResourceParts}（订阅 MCP/Skill + 自建 MCP/Skill
 * 的资源签名 + 会话级圈选 MCP），任一变化即指纹变化，触发 P1-5 懒刷新。
 */
public final class BindingFingerprinter {

    private BindingFingerprinter() {
    }

    /**
     * 计算绑定列表的指纹（版本感知）。
     *
     * @param agentVersion 智能体版本（池键/模板版本；null 视为无版本前缀）
     * @param bindings     agent_binding 条目列表（可为 null 或空）
     * @return 64 位十六进制 SHA-256 摘要；无版本且空绑定时返回 "EMPTY"
     */
    public static String fingerprint(String agentVersion, List<AgentBinding> bindings) {
        return fingerprint(agentVersion, bindings, List.of());
    }

    /**
     * 计算绑定列表 + 动态资源集合的指纹（UNIVERSAL 订阅场景）。
     *
     * <p>动态资源签名（如 {@code SUBMCP:1}、{@code OWNSKILL:5}、{@code SESMCP:9}）
     * 与绑定条目一同排序哈希——集合增删即指纹变化，与 Toolkit 实际加载来源
     * （订阅 MCP/Skill、自建 MCP/Skill、会话圈选 MCP）保持同源。
     *
     * @param agentVersion        智能体版本（null 视为无版本前缀）
     * @param bindings            agent_binding 条目列表（可为 null 或空）
     * @param dynamicResourceParts 动态资源签名列表（null 视为空）
     * @return 64 位十六进制 SHA-256 摘要；无版本、无绑定且无动态资源时返回 "EMPTY"
     */
    public static String fingerprint(String agentVersion, List<AgentBinding> bindings,
                                      List<String> dynamicResourceParts) {
        List<String> parts = new ArrayList<>();
        if (bindings != null && !bindings.isEmpty()) {
            for (AgentBinding b : bindings) {
                String ver = b.getResourceVersion() != null ? String.valueOf(b.getResourceVersion()) : "0";
                parts.add(b.getResourceType() + ":" + b.getResourceId() + ":" + ver);
            }
        }
        if (dynamicResourceParts != null && !dynamicResourceParts.isEmpty()) {
            parts.addAll(dynamicResourceParts);
        }
        if (parts.isEmpty() && (agentVersion == null || agentVersion.isEmpty())) {
            return "EMPTY";
        }
        parts.sort(String::compareTo);
        String versionPrefix = (agentVersion != null && !agentVersion.isEmpty())
                ? "v" + agentVersion + "|" : "";
        return sha256(versionPrefix + String.join("|", parts));
    }

    /**
     * 计算绑定列表的指纹（无版本前缀，供跨版本比较的场景使用）。
     *
     * @param bindings agent_binding 条目列表（可为 null 或空）
     * @return 64 位十六进制 SHA-256 摘要；空绑定时返回 "EMPTY"
     */
    public static String fingerprint(List<AgentBinding> bindings) {
        return fingerprint(null, bindings);
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
