package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.ReviseRequest;
import lyjew.com.lyclaw.task.TaskPlan;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "lyclaw-plan-service", path = "/api/plan")
public interface PlanFeignClient {

    @PostMapping("/plan")
    TaskPlan plan(@RequestBody PlanRequest request);

    @PostMapping("/revise")
    TaskPlan revise(@RequestBody ReviseRequest request);
}
