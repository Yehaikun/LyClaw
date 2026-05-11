package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.ReviseRequest;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.tracing.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 计划服务Feign远程调用客户端。
 *
 * <p>通过Spring Cloud OpenFeign声明式调用lyclaw-plan-service微服务，
 * 提供任务规划、计划修订、任务分解、计划验证、依赖图构建和进度查询等功能。
 * 使用{@link FeignConfig}配置统一的链路追踪拦截器。</p>
 *
 * <p>计划服务是Agent的决策中枢，负责将复杂任务分解为可执行的子任务序列，
 * 构建任务间的依赖关系图（DAG），并在执行过程中跟踪进度。
 * 服务路径前缀：/api/plan</p>
 *
 * @author lyjew
 */
@FeignClient(name = "lyclaw-plan-service", path = "/api/plan", configuration = FeignConfig.class)
public interface PlanFeignClient {

    /**
     * 创建新的任务计划。
     *
     * @param request 计划请求，包含任务描述、约束条件等
     * @return 生成的计划内容
     */
    @PostMapping("/plan")
    Map<String, Object> plan(@RequestBody PlanRequest request);

    /**
     * 修订已有的任务计划。
     *
     * @param request 修订请求，包含计划ID、修订原因和新的约束条件
     * @return 修订后的任务计划
     */
    @PostMapping("/revise")
    TaskPlan revise(@RequestBody ReviseRequest request);

    /**
     * 将复杂任务分解为子任务。
     *
     * @param request 分解请求，包含待分解的任务描述
     * @return 分解后的子任务列表及其关系
     */
    @PostMapping("/decompose")
    Map<String, Object> decompose(@RequestBody Map<String, Object> request);

    /**
     * 验证计划的可行性和正确性。
     *
     * @param planBody 待验证的计划内容
     * @return 验证结果，包含是否可行、问题列表等
     */
    @PostMapping("/validate")
    Map<String, Object> validate(@RequestBody Map<String, Object> planBody);

    /**
     * 构建任务依赖关系图（DAG）。
     *
     * @param request 请求参数，包含子任务列表及其依赖关系
     * @return 构建的依赖图
     */
    @PostMapping("/graph")
    Map<String, Object> buildGraph(@RequestBody Map<String, Object> request);

    /**
     * 列出所有可用的规划策略。
     *
     * @return 策略列表，包含策略名称和描述
     */
    @GetMapping("/strategies")
    java.util.List<Map<String, String>> listStrategies();

    /**
     * 查询指定计划的执行进度。
     *
     * @param planId 计划ID
     * @return 执行进度信息，包含已完成/剩余任务数、当前状态等
     */
    @GetMapping("/progress/{planId}")
    Map<String, Object> getProgress(@PathVariable("planId") String planId);
}
