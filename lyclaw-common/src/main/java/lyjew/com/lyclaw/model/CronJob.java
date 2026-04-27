package lyjew.com.lyclaw.model;

import cn.hutool.core.util.IdUtil;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.time.LocalDateTime;

/**
 * 定时任务
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CronJob extends BaseDTO {

    private String name;               // 任务名称
    private String cronExpr;           // Cron表达式
    private String prompt;            // 执行时的prompt
    private String model;             // 使用的模型

    @Builder.Default
    private boolean enabled=true;          // 是否启用
    private String lastRunTime;       // 上次执行时间
    private String lastRunStatus;     // 上次执行状态
    private String lastRunResult;     // 上次执行结果
    private String nextRunTime;       // 下次执行时间
}
