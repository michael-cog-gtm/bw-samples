package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!standins")
public class DemoController {
    private final DemoAdminClient admin;

    public DemoController(DemoAdminClient admin) {
        this.admin = admin;
    }

    @GetMapping("/api/demo/rows")
    public ResponseEntity<JsonNode> rows() {
        return admin.request("/admin/rows", null);
    }

    @GetMapping("/api/demo/bureaus")
    public ResponseEntity<JsonNode> bureaus() {
        return admin.request("/admin/bureaus", null);
    }

    @PostMapping("/api/demo/bureaus/{bureau}")
    public ResponseEntity<JsonNode> mode(@PathVariable BureauServers.Bureau bureau,
                                          @RequestBody AdminController.ModeRequest request) {
        if (request.mode() == null || (request.delayMs() != null
                && (request.delayMs() < 0 || request.delayMs() > 60000))) {
            return ResponseEntity.badRequest().body(Mappings.object().put("error", "Choose a valid connection mode and delay (0–60000 ms)."));
        }
        return admin.request("/admin/bureaus/" + bureau, Mappings.object()
                .put("mode", request.mode().name()).put("delayMs",
                        request.delayMs() == null ? 1600 : request.delayMs()));
    }

    @PostMapping("/api/demo/reset")
    public ResponseEntity<JsonNode> reset() {
        return admin.request("/admin/reset", Mappings.object());
    }
}
