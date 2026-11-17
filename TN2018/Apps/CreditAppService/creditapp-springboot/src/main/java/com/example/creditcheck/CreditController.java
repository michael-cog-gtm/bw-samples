package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!standins")
public class CreditController {
    private final CreditService service;

    public CreditController(CreditService service) {
        this.service = service;
    }

    @PostMapping(value = "/creditdetails", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> check(@RequestBody JsonNode input) {
        if (!input.isObject()) {
            return ResponseEntity.badRequest().body(Mappings.object().put("error", "expected JSON object"));
        }
        CreditService.Result result = service.check(input);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/api/last-trace")
    public ResponseEntity<CreditService.RequestTrace> lastTrace() {
        return ResponseEntity.ok(service.lastTrace());
    }
}
