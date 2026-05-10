package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CronJob extends BaseDTO {

    private String name;
    private String cronExpr;
    private String prompt;
    private String model;

    @Builder.Default
    private boolean enabled = true;

    private String lastRunTime;
    private String lastRunStatus;
    private String lastRunResult;
    private String nextRunTime;
}
