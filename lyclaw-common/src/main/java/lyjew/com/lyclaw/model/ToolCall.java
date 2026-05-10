package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall extends BaseDTO {

    private String toolCallId;
    private String name;
    private String description;
    private String arguments;
    private String result;
}
