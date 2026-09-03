package com.aegis.runtime.service.sandbox;

import org.springframework.stereotype.Component;

import java.util.Map;

/** aegis_execute 工具 → 沙箱内 python 执行命令 */
@Component
public class AegisExecuteSandboxHandler implements SandboxToolHandler {

    @Override
    public String toolName() {
        return "aegis_execute";
    }

    @Override
    public String toSandboxCommand(Map<String, Object> params) {
        String code = (String) params.getOrDefault("code", "");
        String language = (String) params.getOrDefault("language", "python");
        return switch (language.toLowerCase()) {
            case "python" -> "python3 -c " + shellQuote(code);
            case "bash", "shell", "sh" -> "bash -c " + shellQuote(code);
            default -> code;
        };
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\''") + "'";
    }
}
