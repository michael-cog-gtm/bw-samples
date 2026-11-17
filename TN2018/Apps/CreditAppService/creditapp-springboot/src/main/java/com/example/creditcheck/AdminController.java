package com.example.creditcheck;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.catalina.LifecycleException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("standins")
public class AdminController {
    public record ModeRequest(BureauServers.Mode mode, Long delayMs) { }
    private final BureauServers servers;
    private final StandinStore store;

    public AdminController(BureauServers servers, StandinStore store) {
        this.servers = servers;
        this.store = store;
    }

    @GetMapping("/admin/bureaus")
    public Map<BureauServers.Bureau, BureauServers.State> states() {
        return servers.states();
    }

    @PostMapping("/admin/bureaus/{bureau}")
    public BureauServers.State mode(@PathVariable BureauServers.Bureau bureau,
                                    @RequestBody ModeRequest request) throws LifecycleException {
        if (request.mode() == null) {
            throw new IllegalArgumentException("mode is required");
        }
        return servers.mode(bureau, request.mode(), request.delayMs());
    }

    @GetMapping("/admin/rows")
    public List<ObjectNode> rows() {
        return store.rows();
    }

    @PostMapping("/admin/reset")
    public Map<BureauServers.Bureau, BureauServers.State> reset() throws IOException, LifecycleException {
        servers.reset();
        return servers.states();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ObjectNode> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Mappings.object().put("error", error.getMessage()));
    }
}
