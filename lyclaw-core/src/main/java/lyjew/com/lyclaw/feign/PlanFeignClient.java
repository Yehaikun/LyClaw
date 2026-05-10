package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.ReviseRequest;
import lyjew.com.lyclaw.task.TaskPlan;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "lyclaw-plan-service", path = "/api/plan")
public interface PlanFeignClient {

    @PostMapping("/plan")
    Map<String, Object> plan(@RequestBody PlanRequest request);

    @PostMapping("/revise")
    TaskPlan revise(@RequestBody ReviseRequest request);

    @PostMapping("/decompose")
    Map<String, Object> decompose(@RequestBody Map<String, Object> request);

    @PostMapping("/validate")
    Map<String, Object> validate(@RequestBody Map<String, Object> planBody);

    @PostMapping("/graph")
    Map<String, Object> buildGraph(@RequestBody Map<String, Object> request);

    @GetMapping("/strategies")
    java.util.List<Map<String, String>> listStrategies();

    @GetMapping("/progress/{planId}")
    Map<String, Object> getProgress(@PathVariable("planId") String planId);
}
