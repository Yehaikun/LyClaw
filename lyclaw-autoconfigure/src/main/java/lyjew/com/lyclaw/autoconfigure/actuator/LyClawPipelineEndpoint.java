package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LyClaw Pipeline 管道信息 Actuator 端点，通过 HTTP 暴露当前运行时所有管道阶段的
 * 注册顺序和元数据信息。
 *
 * <p>该端点通过 Spring Boot Actuator 的 {@code @Endpoint} 和 {@code @ReadOperation}
 * 机制对外提供只读的管道阶段查询接口，端点 ID 为 {@code lyclaw-pipeline}，访问路径为
 * {@code /actuator/lyclaw-pipeline}。端点在运行时动态收集所有实现了
 * {@link lyjew.com.lyclaw.pipeline.ReactivePipelineStage} 接口的 Spring Bean，
 * 并按执行优先级（order）升序排列后返回给调用方。</p>
 *
 * <p><b>管道阶段发现机制：</b>端点的构造方法接收一个 {@code List<ReactivePipelineStage>}
 * 参数，Spring 容器会自动注入所有实现了 ReactivePipelineStage 接口的 Bean 实例。
 * 使用 {@code @Autowired(required = false)} 注解确保即使没有任何管道阶段注册也不会
 * 导致启动失败，此时端点返回 {@code "available": false} 状态。</p>
 *
 * <p><b>返回数据结构：</b>每个管道阶段返回三个关键字段——名称（name，来自
 * {@code getStageName()} 方法）、执行顺序（order，来自 {@code getOrder()} 方法）
 * 和实现类全限定名（class），这些信息有助于运维人员快速理解当前管道的数据处理流程
 * 和阶段执行顺序，便于排查管道执行中的问题。</p>
 *
 * <p><b>使用场景：</b>此端点主要用于运维监控和故障排查，当管道执行出现异常时，
 * 可以通过此端点快速确认：哪些阶段已正确注册、阶段间执行顺序是否符合预期、
 * 是否有自定义阶段被意外排除等。</p>
 */
@Endpoint(id = "lyclaw-pipeline")
public class LyClawPipelineEndpoint {

    private final List<ReactivePipelineStage> stages;

    @Autowired
    public LyClawPipelineEndpoint(@Autowired(required = false) List<ReactivePipelineStage> stages) {
        this.stages = stages;
    }

    @ReadOperation
    public Map<String, Object> pipeline() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (stages == null || stages.isEmpty()) {
            result.put("available", false);
            result.put("reason", "No ReactivePipelineStage beans discovered");
            return result;
        }
        result.put("stageCount", stages.size());
        result.put("stages", stages.stream()
                .sorted(java.util.Comparator.comparingInt(ReactivePipelineStage::getOrder))
                .map(stage -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", stage.getStageName());
                    s.put("order", stage.getOrder());
                    s.put("class", stage.getClass().getName());
                    return s;
                })
                .toList());
        return result;
    }
}
