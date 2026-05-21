package com.axonlink.ai.code.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 原生 git 命令执行器（用户选定方案：本地 clone + 原生 git，非 JGit）。
 * 统一 UTF-8 读取、关闭 core.quotepath（中文路径不转义）、分离 drain stderr 防止管道死锁、命令级超时。
 */
@Component
public class GitCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitCommandExecutor.class);

    /** 单条 git 命令最长执行时间，避免凭证错误等场景下挂死调度线程。 */
    private static final long COMMAND_TIMEOUT_MINUTES = 10;

    public static class GitException extends RuntimeException {
        public GitException(String message) { super(message); }
    }

    public static class GitResult {
        public final int exitCode;
        public final List<String> stdout;
        public final String stderr;
        public GitResult(int exitCode, List<String> stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
        public boolean ok() { return exitCode == 0; }
    }

    /** 执行 git 命令，失败（非 0 退出）抛 GitException。 */
    public GitResult exec(String gitExecutable, File workDir, List<String> gitArgs) {
        GitResult r = execAllowFail(gitExecutable, workDir, gitArgs);
        if (!r.ok()) {
            throw new GitException("git " + String.join(" ", gitArgs)
                    + " 退出码=" + r.exitCode + " stderr=" + abbreviate(r.stderr));
        }
        return r;
    }

    /** 执行 git 命令，非 0 退出不抛异常（调用方自行判断，如 blame 二进制文件）。 */
    public GitResult execAllowFail(String gitExecutable, File workDir, List<String> gitArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable);
        // 全局参数：中文路径不八进制转义；log/输出统一 UTF-8
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.add("-c");
        cmd.add("i18n.logOutputEncoding=UTF-8");
        cmd.addAll(gitArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir);
        }
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();
            final Process p = process;

            StringBuilder errBuf = new StringBuilder();
            Thread errDrain = new Thread(() -> drain(p.getErrorStream(), errBuf));
            errDrain.setDaemon(true);
            errDrain.start();

            List<String> out = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.add(line);
                }
            }

            boolean finished = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new GitException("git " + String.join(" ", gitArgs) + " 执行超时");
            }
            errDrain.join(2000);
            return new GitResult(process.exitValue(), out, errBuf.toString());
        } catch (IOException e) {
            throw new GitException("无法执行 git（请确认服务器已安装 git 且在 PATH 中）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new GitException("git 执行被中断");
        }
    }

    /** 便捷重载：可变参数。 */
    public GitResult exec(String gitExecutable, File workDir, String... gitArgs) {
        return exec(gitExecutable, workDir, Arrays.asList(gitArgs));
    }

    private void drain(InputStream in, StringBuilder buf) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                buf.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // stderr 读取失败不影响主流程
        }
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
