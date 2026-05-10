package lyjew.com.lyclaw.controller.v1;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.facade.LyClawFacade;
import lyjew.com.lyclaw.model.ToolDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tool management REST API (v1).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final LyClawFacade facade;

    public ToolController(LyClawFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public ResponseEntity<List<ToolDefinition>> listTools() {
        return ResponseEntity.ok(facade.getTools());
    }
}
