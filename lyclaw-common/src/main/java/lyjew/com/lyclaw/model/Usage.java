package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * Token用量
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class Usage extends BaseDTO {

    private int promptTokens;      // 输入token数
    private int completionTokens;  // 输出token数
    private int totalTokens;       // 总token数

    /**
     * 便捷构造方法——自动计算 totalTokens
     */
    public Usage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    /**
     * 静态工厂方法——创建 Usage 并自动计算 total
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens);
    }
}