package lyjew.com.lyclaw.model;

import lombok.*;
import lyjew.com.lyclaw.base.BaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class Usage extends BaseDTO {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

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

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens);
    }
}
