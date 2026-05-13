package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 反思请求实体，封装触发反思引擎执行所需的全部信息。
 *
 * <p><b>在反思系统中的角色</b>：ReflectRequest 是反思链路的输入对象，
 * 由反思系统的调用方（如 Agent 执行器、质量监控定时任务、管理后台等）构造，
 * 传递给 {@link ReflectionEngine#reflect} 或类似的入口方法。
 * 它包含了反思引擎完成一次全面评估所需的全部上下文信息。</p>
 *
 * <p><b>反思请求包含的核心信息</b>：
 * <ul>
 *   <li><b>sessionId（会话标识）</b> — 标识此次反思所属的会话，用于关联反思报告
 *       与具体的对话记录。在多用户并发场景下，sessionId 是隔离不同用户反思结果的关键字段。</li>
 *   <li><b>output（模型实际输出）</b> — 待评估的模型生成文本，是反思引擎的主要分析对象。
 *       可以是单轮对话的回复、多轮对话的累积输出、或工具调用的结果摘要。
 *       其内容质量直接决定了后续质量评估和错误检测的结果。</li>
 *   <li><b>expectedOutput（期望输出）</b> — 理想情况下模型应该生成的输出文本，
 *       作为质量评估的参考基准。该字段为可选字段，当有明确的标准答案或人工标注的期望输出时
 *       填入，用于计算准确性和完整性评分。在无明确期望输出的场景（如开放式对话）下，
 *       该字段可为 null，反思引擎将使用规则化和统计化的方法进行评估。</li>
 *   <li><b>context（上下文信息）</b> — 附加的上下文信息文本，帮助反思引擎更好地
 *       理解模型的输出场景。可以包含对话历史摘要、用户意图、当前任务描述、
 *       已执行的操作记录等。丰富的上下文信息有助于提高错误检测的准确率，
 *       减少误报。</li>
 * </ul>
 *
 * <p><b>典型使用流程</b>：</p>
 * <pre>
 * ReflectRequest request = ReflectRequest.builder()
 *     .sessionId("sess-abc123")
 *     .output("根据您的需求，我建议采用...")
 *     .expectedOutput("根据您的需求，推荐方案A...")
 *     .context("用户正在咨询技术选型，当前项目为 Java Spring Boot 微服务架构")
 *     .build();
 * ReflectionReport report = reflectionEngine.reflect(context, result);
 * </pre>
 *
 * @see ReflectionEngine 反思引擎，接收此类作为输入
 * @see ReflectionReport 反思报告，反思引擎处理后的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectRequest {
    /** 会话标识，用于关联反思结果与具体会话 */
    private String sessionId;
    /** 模型实际输出文本，反思的主要分析对象 */
    private String output;
    /** 期望输出文本，作为质量评估的参考基准（可选） */
    private String expectedOutput;
    /** 附加上下文信息，帮助理解模型输出场景（可选） */
    private String context;
}
