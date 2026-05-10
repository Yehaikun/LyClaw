package lyjew.com.lyclaw.controller.v1;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.facade.LyClawFacade;
import lyjew.com.lyclaw.model.ModelConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Model provider management REST API (v1).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final LyClawFacade facade;

    public ModelController(LyClawFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/providers")
    public ResponseEntity<Set<String>> listProviders() {
        return ResponseEntity.ok(facade.getProviders());
    }

    @GetMapping("/configs")
    public ResponseEntity<List<ModelConfig>> listConfigs() {
        return ResponseEntity.ok(facade.getModelConfigs());
    }

    @PostMapping("/configs")
    public ResponseEntity<Void> configureModel(@RequestBody ModelConfig config) {
        facade.configureModel(config);
        return ResponseEntity.ok().build();
    }
}
