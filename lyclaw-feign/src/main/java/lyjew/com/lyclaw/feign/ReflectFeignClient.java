package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.tracing.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 反思服务Feign远程调用客户端。
 *
 * <p>通过Spring Cloud OpenFeign声明式调用lyclaw-reflect-service微服务，
 * 提供反思、评估和错误检测等自我审查功能。
 * 使用{@link FeignConfig}配置统一的链路追踪拦截器。</p>
 *
 * <p>反思服务用于对Agent的输出结果进行质量评估和错误检测，
 * 是实现自我纠错能力的关键组件。服务路径前缀：/api/reflect</p>
 *
 * @author lyjew
 */
@FeignClient(name = "lyclaw-reflect-service", path = "/api/reflect", configuration = FeignConfig.class)
public interface ReflectFeignClient {

    /**
     * 对内容进行反思，生成反思报告。
     *
     * @param request 反思请求，包含待反思的内容和上下文
     * @return 反思报告，包含分析结果和改进建议
     */
    @PostMapping("/reflect")
    ReflectionReport reflect(@RequestBody ReflectRequest request);

    /**
     * 评估内容质量。
     *
     * @param request 评估请求参数
     * @return 评估结果，包含评分、详细反馈等信息
     */
    @PostMapping("/evaluate")
    Map<String, Object> evaluate(@RequestBody Map<String, Object> request);

    /**
     * 检测内容中的错误。
     *
     * @param request 错误检测请求参数
     * @return 检测到的错误列表，每个错误包含位置、类型、描述等信息
     */
    @PostMapping("/detect-errors")
    List<Map<String, Object>> detectErrors(@RequestBody Map<String, Object> request);
}
