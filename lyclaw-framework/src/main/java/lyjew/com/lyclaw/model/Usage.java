package lyjew.com.lyclaw.model;

import lombok.*;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * 令牌使用统计模型，记录一次 AI 模型调用的令牌消耗情况。
 *
 * 包含提示词令牌数（promptTokens）、补全令牌数（completionTokens）以及
 * 自动计算的总令牌数（totalTokens）。自定义 setter 方法在设置单个令牌数时
 * 自动更新 totalTokens 以保持三者一致性。继承自 BaseDTO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class Usage extends BaseDTO {

    /** 提示词部分消耗的令牌数 */
    private int promptTokens;
    /** 模型补全生成消耗的令牌数 */
    private int completionTokens;
    /** 总令牌数（= promptTokens + completionTokens） */
    private int totalTokens;

    /**
     * 构造使用统计并自动计算总令牌数。
     *
     * @param promptTokens     提示词令牌数
     * @param completionTokens 补全令牌数
     */
    public Usage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        // 自动计算总令牌数
        this.totalTokens = promptTokens + completionTokens;
    }

    /**
     * 设置补全令牌数，同时自动更新总令牌数。
     *
     * @param completionTokens 补全令牌数
     */
    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
        // 重新计算总令牌数以保持一致性
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    /**
     * 设置提示词令牌数，同时自动更新总令牌数。
     *
     * @param promptTokens 提示词令牌数
     */
    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
        // 重新计算总令牌数以保持一致性
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    /**
     * 静态工厂方法，快速创建 Usage 实例。
     *
     * @param promptTokens     提示词令牌数
     * @param completionTokens 补全令牌数
     * @return 新的 Usage 实例，totalTokens 自动计算
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens);
    }
}
