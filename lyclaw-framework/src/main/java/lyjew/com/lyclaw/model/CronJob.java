package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * 定时任务模型，用于配置和管理周期性执行的 AI 对话任务。
 *
 * 每条记录代表一个定时触发的 AI 对话任务，包含 Cron 表达式、提示词内容、
 * 使用的模型以及最近一次运行的状态和结果跟踪信息。继承自 BaseDTO，
 * 使用 Lombok @Data/@SuperBuilder 自动生成数据访问方法。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CronJob extends BaseDTO {

    /** 任务名称，用于标识和显示 */
    private String name;
    /** Cron 表达式，定义任务的触发周期 */
    private String cronExpr;
    /** 定时发送给 AI 的提示词内容 */
    private String prompt;
    /** 指定使用的 AI 模型名称 */
    private String model;

    /** 是否启用此定时任务，默认启用 */
    @Builder.Default
    private boolean enabled = true;

    /** 最近一次运行时间 */
    private String lastRunTime;
    /** 最近一次运行状态（success/failure 等） */
    private String lastRunStatus;
    /** 最近一次运行的输出结果 */
    private String lastRunResult;
    /** 预计下一次运行时间 */
    private String nextRunTime;
}
