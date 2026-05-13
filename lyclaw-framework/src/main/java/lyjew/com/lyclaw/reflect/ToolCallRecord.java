package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用记录实体，记录单次工具调用的详细信息。
 *
 * <p><b>在反思系统中的角色</b>：ToolCallRecord 是工具调用层面的最小粒度审计单元。
 * 在 Agent 执行过程中，每次对工具的调用（如文件读取、网络搜索、代码执行、
 * 数据库查询等）都会生成一条 ToolCallRecord 记录。
 * 反思引擎将这些记录聚合分析，以识别工具使用的成功/失败模式、
 * 耗时分布、以及需要调整的工具调用策略。</p>
 *
 * <p><b>记录的五大维度</b>：
 * <ul>
 *   <li><b>toolName（工具名称）</b> — 标识被调用的具体工具。
 *       如 "web_search"、"file_read"、"code_executor"、"knowledge_search" 等。
 *       反思引擎根据工具名称分组统计，识别不同工具的使用频率和失败率分布。</li>
 *   <li><b>success（是否成功）</b> — 工具调用是否成功完成。
 *       注意此字段反映的是工具自身是否成功执行（如 API 返回了 200 OK），
 *       而非工具返回的结果是否符合预期（后者需要质量评估来判断）。
 *       成功但结果错误的情况对应 TOOL_FAILURE_PATTERN 错误类型。</li>
 *   <li><b>durationMs（耗时毫秒）</b> — 工具调用的执行耗时。
 *       用于识别慢查询和性能瓶颈，如某个知识检索工具持续耗时超过 5 秒，
 *       可能需要优化、缓存或替换。</li>
 *   <li><b>output（输出内容）</b> — 工具调用返回的文本结果。
 *       反思引擎分析此内容以判断结果的质量和相关性，
 *       也用于错误检测（如检测输出是否为空、格式是否异常等）。</li>
 *   <li><b>errorMessage（错误信息）</b> — 工具调用失败时的详细错误消息。
 *       包含异常堆栈、HTTP 状态码、超时信息等。
 *       反思引擎通过分析错误消息的模式来识别系统性问题
 *       （如某工具频繁因超时失败 → 建议增加超时或切换替代工具）。</li>
 * </ul>
 *
 * <p><b>与反思引擎的协作</b>：反思引擎在分析工具调用模式时，
 * 会对一批 ToolCallRecord 进行聚合统计：</p>
 * <ul>
 *   <li>计算各工具的成功率和平均耗时</li>
 *   <li>识别频繁失败的工具及其失败原因分布</li>
 *   <li>检测是否存在工具调用的死循环（同一工具被反复调用始终失败）</li>
 *   <li>评估工具调用的顺序是否合理（如是否在读取文件前搜索文件路径）</li>
 *   <li>如果某个工具的成功率持续低于阈值，由 StrategyAdjuster 生成
 *       ADD_TOOL_CALL（增加替代工具）或 REWRITE_PROMPT（重写工具使用提示）建议</li>
 * </ul>
 *
 * @see DetectedError 当工具调用出现模式化失败时，生成 TOOL_FAILURE_PATTERN 类型错误
 * @see ReflectionEngine 反思引擎，聚合分析工具调用记录
 * @see StrategyAdjuster 策略调整器，根据工具调用分析结果生成调整方案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    /** 被调用的工具名称 */
    private String toolName;
    /** 工具调用是否成功完成 */
    private boolean success;
    /** 工具调用耗时（毫秒） */
    private long durationMs;
    /** 工具返回的输出内容 */
    private String output;
    /** 失败时的错误消息（成功时为 null） */
    private String errorMessage;
}
