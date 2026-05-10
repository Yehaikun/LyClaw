package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "lyclaw-reflect-service", path = "/api/reflect")
public interface ReflectFeignClient {

    @PostMapping("/reflect")
    ReflectionReport reflect(@RequestBody ReflectRequest request);

    @PostMapping("/evaluate")
    Map<String, Object> evaluate(@RequestBody Map<String, Object> request);

    @PostMapping("/detect-errors")
    List<Map<String, Object>> detectErrors(@RequestBody Map<String, Object> request);
}
