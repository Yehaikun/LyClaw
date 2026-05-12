package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.action.util.CommandExecutor;
import lyjew.com.lyclaw.action.util.CommandExecutor.CommandResult;
import lyjew.com.lyclaw.annotation.tool.Param;
import lyjew.com.lyclaw.annotation.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 脚本执行工具，将 AI 生成的代码写入临时文件并通过对应解释器执行。
 *
 * <p>支持 Python、JavaScript(Node) 和 Bash。
 * 复用 {@link CommandExecutor} 提供超时保护（30秒）和输出截断（10000字符）。
 */
@Tool(name = "execute_script",
      description = "将代码写入临时文件并通过对应解释器执行。支持 Python(python3)、JavaScript(node)、Bash。"
                  + "适合 AI 需要运行完整脚本的场景（数据分析、文件处理、复杂逻辑等）",
      readonly = false,
      group = "builtin")
public class AnnotatedScriptTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 10000;
    private static final Map<String, String[]> INTERPRETERS = Map.of(
            "python", new String[]{"python3", ".py"},
            "node", new String[]{"node", ".js"},
            "bash", new String[]{"bash", ".sh"}
    );

    public String executeScript(
            @Param(name = "language", description = "编程语言: python, node, bash", required = true)
            String language,
            @Param(name = "script", description = "脚本源码内容", required = true)
            String script) {

        String lang = language != null ? language.toLowerCase().trim() : "";
        String[] info = INTERPRETERS.get(lang);
        if (info == null) {
            return "不支持的语言: " + language + "。支持: python, node, bash";
        }

        String interpreter = info[0];
        String suffix = info[1];
        Path tmpFile = null;

        try {
            tmpFile = Files.createTempFile("lyclaw_script_", suffix);
            Files.writeString(tmpFile, script, StandardCharsets.UTF_8);
            tmpFile.toFile().setExecutable(true);

            String command = interpreter + " " + tmpFile.toAbsolutePath();
            CommandResult result = CommandExecutor.execute(command, TIMEOUT_SECONDS, MAX_OUTPUT_LENGTH);

            if (result.timedOut()) {
                return "脚本执行超时（" + TIMEOUT_SECONDS + "秒）";
            }
            return "[exit=" + result.exitCode() + "] " + result.output();

        } catch (Exception e) {
            return "脚本执行异常: " + e.getMessage();
        } finally {
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
