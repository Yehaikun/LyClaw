package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Memory extends BaseDTO {

    @Builder.Default
    private String content = "";

    @Builder.Default
    private String title = "记忆";

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
