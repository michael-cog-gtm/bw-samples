package demo.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Profile("!standin")
@RestController
public class CreditController {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String equifax;
    private final String experian;
    private final Duration timeout;

    public CreditController(ObjectMapper mapper,
            @Value("${equifax.base-url:http://localhost:13080}") String equifax,
            @Value("${experian.base-url:http://localhost:7080}") String experian,
            @Value("${bureau.timeout-ms:1000}") long timeoutMs) {
        this.mapper = mapper;
        this.equifax = equifax;
        this.experian = experian;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public record Result(JsonNode body, String failure) { }

    private CompletableFuture<Result> call(String base, ObjectNode body, String query) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/creditscore" + query))
                .timeout(timeout).header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        return new Result(null, cause instanceof java.util.concurrent.TimeoutException
                                || cause instanceof java.net.http.HttpTimeoutException ? "timeout" : "connection error");
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        return new Result(null, "HTTP " + response.statusCode());
                    try {
                        JsonNode parsed = mapper.readTree(response.body());
                        if (parsed == null || !parsed.isObject()) return new Result(null, "invalid JSON object");
                        return new Result(parsed, null);
                    } catch (Exception exception) { return new Result(null, "unparseable JSON"); }
                });
    }

    @PostMapping("/creditdetails")
    public ResponseEntity<?> details(@RequestBody JsonNode input) {
        String query = input.hasNonNull("SSN") ? "?ssn=" + URLEncoder.encode(input.get("SSN").asText(), StandardCharsets.UTF_8) : "";
        var eq = call(equifax, Mappings.equifaxInput(input), "");
        var ex = call(experian, Mappings.experianInput(input), query);
        CompletableFuture.allOf(eq, ex).join();
        Map<String, String> failures = new LinkedHashMap<>();
        if (eq.join().failure() != null) failures.put("Equifax", eq.join().failure());
        if (ex.join().failure() != null) failures.put("Experian", ex.join().failure());
        if (!failures.isEmpty()) return ResponseEntity.status(502).body(Map.of("error", "Bureau request failed", "providers", failures));
        return ResponseEntity.ok(Mappings.aggregate(eq.join().body(), ex.join().body()));
    }
}
