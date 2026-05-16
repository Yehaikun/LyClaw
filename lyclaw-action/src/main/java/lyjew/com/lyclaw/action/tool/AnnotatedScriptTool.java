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

    /**
     * 执行一段脚本代码，将其写入临时文件后通过对应的解释器在子进程中运行。
     *
     * <p>支持的编程语言及对应解释器：
     * <ul>
     *   <li><b>python</b> — 使用 {@code python3} 解释器，临时文件后缀为 {@code .py}</li>
     *   <li><b>node</b> — 使用 {@code node} 解释器，临时文件后缀为 {@code .js}</li>
     *   <li><b>bash</b> — 使用 {@code bash} 解释器，临时文件后缀为 {@code .sh}</li>
     * </ul>
     * 语言参数不区分大小写，传入不支持的语言时会返回错误提示。
     * </p>
     *
     * <p>执行流程：
     * <ol>
     *   <li>根据 {@code language} 参数查找对应的解释器和文件后缀，若语言不支持则直接返回错误</li>
     *   <li>通过 {@link java.nio.file.Files#createTempFile} 创建临时文件（前缀为 {@code lyclaw_script_}），
     *       以 UTF-8 编码将脚本内容写入文件，并设置文件为可执行权限</li>
     *   <li>拼装命令行：解释器路径 + 临时文件的绝对路径，委托给
     *       {@link CommandExecutor#execute(String, int, int)} 在独立子进程中执行，
     *       超时时间为 30 秒，输出限制在 10000 字符以内</li>
     *   <li>无论执行成功、失败、超时或异常，都会在 finally 块中删除临时文件，避免残留</li>
     * </ol>
     * </p>
     *
     * <p>透明返回脚本的标准输出和标准错误内容（stderr 已合并到 stdout）。
     * 仅超时或发生异常时返回错误描述，不根据退出码判断成败。</p>
     *
     * @param language 编程语言标识，支持 {@code "python"}、{@code "node"}、{@code "bash"}（不区分大小写）
     * @param script   要执行的脚本源代码完整内容，应为合法的对应语言脚本
     * @return 脚本执行的原始输出文本，超时或异常时返回错误描述
     */
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
            return result.output().isEmpty() ? "(无输出)" : result.output();

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
