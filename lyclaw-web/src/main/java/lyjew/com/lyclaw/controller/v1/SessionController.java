package lyjew.com.lyclaw.controller.v1;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.facade.LyClawFacade;
import lyjew.com.lyclaw.model.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Session management REST API (v1).
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final LyClawFacade facade;

    public SessionController(LyClawFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public ResponseEntity<List<Session>> listSessions() {
        return ResponseEntity.ok(facade.getSessions());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Session> getSession(@PathVariable String id) {
        Session session = facade.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(session);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        facade.deleteSession(id);
        return ResponseEntity.noContent().build();
    }
}
