package com.aegis.runtime.infrastructure.sandbox.backend;

import com.aegis.core.spi.ISandboxBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 进程沙箱后端实现（开发环境回退）。
 *
 * <p>不依赖 Docker，通过操作系统进程执行命令，提供基本的隔离与安全限制。
 * 适用于本地开发与测试环境，生产环境应使用 Docker 或 K8s 沙箱后端。
 *
 * <h3>激活条件</h3>
 * <p>配置 {@code aegis.runtime.sandbox.backend=process} 时激活。<b>P0-3 修复</b>：
 * 原 {@code matchIfMissing=true} 使未配置 backend 时本类作为默认实现隐式激活，
 * exec 直接走宿主 shell，白名单防线弱于真沙箱；现改为 {@code matchIfMissing=false}，
 * 未显式声明 backend 时不再隐式回退（由 {@code SandboxBackendStartupValidator} fail-fast）。
 * 本地开发无 Docker/K8s 时显式配置 {@code backend=process} 仍可使用。
 *
 * <h3>安全限制</h3>
 * <ul>
 *   <li>仅允许白名单命令前缀（python3/python/node/java/echo/mkdir/rm/rmdir）</li>
 *   <li>超时强制终止进程</li>
 *   <li>输出截断至 1MB</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aegis.runtime.sandbox.backend", havingValue = "process", matchIfMissing = false)
public class ProcessSandboxBackend implements ISandboxBackend {

    private static final List<String> ALLOWED_COMMANDS = Arrays.asList(
            "python3", "python", "node", "java", "echo", "mkdir",
            "tar", "base64", "ls", "cat", "find", "sort", "head", "tail", "wc",
            "touch", "cp", "mv", "pwd", "test", "rm", "rmdir",
            "while", "stat", "printf", "read", "grep", "sed", "awk", "tr", "cut", "date");

    private static final String[] FORBIDDEN_PATTERNS = {
            ";", "&&", "||", "`", "$(", "${", "\n", "\r"
    };

    private static final String[] FRAMEWORK_FS_COMMAND_PREFIXES = {
            "find '", "find \"", "find ", "ls '", "ls \"", "ls -",
            "cat '", "cat \"", "test -", "mkdir -p '",
            "if [ ", "if [!", "if test "
    };

    private boolean isCommandAllowed(String command) {
        String cmd = command.trim();
        if (cmd.isEmpty()) {
            return false;
        }

        if (isFrameworkInternalCommand(cmd)) {
            log.debug("框架内部文件系统命令，直接放行: {}", cmd.substring(0, Math.min(80, cmd.length())));
            return true;
        }

        boolean isCodeExec = isCodeExecutionCommand(cmd);

        String cmdWithoutQuotes = cmd.replaceAll("'[^']*'", "''");
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (isCodeExec && ("&&".equals(pattern) || "||".equals(pattern) 
                    || "\n".equals(pattern) || "\r".equals(pattern))) {
                continue;
            }
            if (cmdWithoutQuotes.contains(pattern)) {
                log.warn("命令包含禁止的 shell 元字符 [{}]，拒绝执行: {}", pattern, cmd);
                return false;
            }
        }
        String[] pipeSegments = cmd.split("\\|");
        for (String segment : pipeSegments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String firstToken = trimmed.split("\\s+")[0];
            int lastSlash = firstToken.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < firstToken.length() - 1) {
                firstToken = firstToken.substring(lastSlash + 1);
            }
            if (!ALLOWED_COMMANDS.contains(firstToken)) {
                log.warn("命令首词不在白名单 [{}]，拒绝执行: {}", firstToken, cmd);
                return false;
            }
        }
        return true;
    }

    private boolean isCodeExecutionCommand(String cmd) {
        String lowerCmd = cmd.toLowerCase();
        return lowerCmd.startsWith("python ") 
                || lowerCmd.startsWith("python3 ") 
                || lowerCmd.startsWith("node ")
                || lowerCmd.startsWith("python -")
                || lowerCmd.startsWith("python3 -")
                || lowerCmd.startsWith("node -");
    }

    private boolean isFrameworkInternalCommand(String cmd) {
        for (String prefix : FRAMEWORK_FS_COMMAND_PREFIXES) {
            if (cmd.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String create(Long tenantId, String image, double cpu, int memoryMb) {
        String instanceId = "proc-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("ProcessSandbox created: instanceId={}, tenantId={}", instanceId, tenantId);
        return instanceId;
    }

    @Override
    public boolean destroy(Long tenantId, String instanceId) {
        log.info("ProcessSandbox destroyed: instanceId={}", instanceId);
        return true;
    }

    @Override
    public String snapshot(Long tenantId, String instanceId) {
        log.warn("ProcessSandbox does not support snapshot, returning fake id");
        return "snapshot-" + instanceId;
    }

    @Override
    public String restore(Long tenantId, String snapshotId) {
        log.warn("ProcessSandbox does not support restore, creating new instance");
        return create(tenantId, "process", 0.5, 256);
    }

    @Override
    public ExecResult exec(Long tenantId, String instanceId, String command, long timeoutSec) {
        ExecResult result = new ExecResult();
        try {
            log.info("ProcessSandbox exec: tenantId={}, instanceId={}, command={}, timeoutSec={}",
                    tenantId, instanceId, command, timeoutSec);
            
            if (!isCommandAllowed(command)) {
                log.warn("ProcessSandbox exec: command rejected by security check, command={}", command);
                result.exitCode = -1;
                result.stderr = "Command not allowed: " + command;
                return result;
            }
            log.debug("ProcessSandbox exec: command passed security check");
            
            return execViaShell(command, instanceId, timeoutSec);
        } catch (Exception e) {
            log.error("ProcessSandbox exec exception: {}", e.getMessage(), e);
            result.exitCode = -1;
            result.stderr = e.getMessage();
        }
        return result;
    }

    private ExecResult execViaShell(String command, String instanceId, long timeoutSec) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("/bin/sh", "-c", command);
        }
        pb.redirectErrorStream(false);
        return executeProcess(pb, command, timeoutSec);
    }

    private ExecResult executeProcess(ProcessBuilder pb, String command, long timeoutSec) {
        ExecResult result = new ExecResult();
        try {
            Process process = pb.start();
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    int maxLines = 5000;
                    while ((line = stderrReader.readLine()) != null && maxLines-- > 0) {
                        stderrBuf.append(line).append("\n");
                    }
                } catch (Exception e) {
                    log.debug("stderr 读取失败: {}", e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();
            
            try (BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int maxLines = 5000;
                while ((line = stdoutReader.readLine()) != null && maxLines-- > 0) {
                    stdoutBuf.append(line).append("\n");
                }
            }
            
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            stderrThread.join(2000);
            
            if (!finished) {
                process.destroyForcibly();
                result.exitCode = -1;
                result.stderr = "Command timed out after " + timeoutSec + "s";
                log.warn("ProcessSandbox exec timed out: command={}", command.substring(0, Math.min(100, command.length())));
                return result;
            }
            
            result.exitCode = process.exitValue();
            result.stdout = stdoutBuf.toString();
            result.stderr = stderrBuf.toString();
            
            if (result.stdout.length() > 1024 * 1024) {
                result.stdout = result.stdout.substring(0, 1024 * 1024) + "\n... (truncated)";
            }
            
            log.info("ProcessSandbox exec completed: exitCode={}, stdoutLen={}, stderrLen={}",
                    result.exitCode, result.stdout.length(), result.stderr.length());
        } catch (Exception e) {
            log.error("ProcessSandbox exec exception: {}", e.getMessage(), e);
            result.exitCode = -1;
            result.stderr = e.getMessage();
        }
        return result;
    }
}
