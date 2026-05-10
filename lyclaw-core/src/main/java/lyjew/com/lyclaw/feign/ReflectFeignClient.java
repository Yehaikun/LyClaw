package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "lyclaw-reflect-service", path = "/api/reflect")
public interface ReflectFeignClient {

    @PostMapping("/reflect")
    ReflectionReport reflect(@RequestBody ReflectRequest request);
}
