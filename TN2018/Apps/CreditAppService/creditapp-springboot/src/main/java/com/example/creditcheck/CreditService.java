package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!standins")
public class CreditService {
    public record CallTrace(String bureau, String url, JsonNode requestPayload, Integer status,
                            long latencyMs, String error) { }
    public record RequestTrace(String id, Instant startedAt, List<CallTrace> calls) { }
    public record BureauError(String bureau, String reason) { }
    public record Result(int status, JsonNode body) { }
    private record Outcome(JsonNode body, CallTrace trace) { }

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;
    private final String equifaxBase;
    private final String experianBase;
    private final AtomicReference<RequestTrace> lastTrace = new AtomicReference<>();

    public CreditService(ObjectMapper mapper,
                         @Value("${credit.equifax-base-url}") String equifaxBase,
                         @Value("${credit.experian-base-url}") String experianBase,
                         @Value("${credit.timeout-ms:1000}") long timeoutMs) {
        this.mapper = mapper;
        this.equifaxBase = equifaxBase.replaceAll("/+$", "");
        this.experianBase = experianBase.replaceAll("/+$", "");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    public RequestTrace lastTrace() {
        return lastTrace.get();
    }

    public Result check(JsonNode input) {
        Instant started = Instant.now();
        String id = UUID.randomUUID().toString();
        String query = input.hasNonNull("SSN") ? "?ssn=" + URLEncoder.encode(
                input.get("SSN").asText(), StandardCharsets.UTF_8) : "";
        CompletableFuture<Outcome> equifax = call("Equifax", equifaxBase + "/creditscore",
                Mappings.equifaxRequest(input));
        CompletableFuture<Outcome> experian = call("Experian", experianBase + "/creditscore" + query,
                Mappings.experianRequest(input));
        CompletableFuture.allOf(equifax, experian).join();
        Outcome eq = equifax.join();
        Outcome ex = experian.join();
        lastTrace.set(new RequestTrace(id, started, List.of(eq.trace(), ex.trace())));
        List<BureauError> errors = new ArrayList<>();
        for (Outcome outcome : List.of(eq, ex)) {
            if (outcome.trace().error() != null) {
                errors.add(new BureauError(outcome.trace().bureau(), outcome.trace().error()));
            }
        }
        if (!errors.isEmpty()) {
            ObjectNode body = Mappings.object();
            body.put("error", "bureau request failed");
            body.set("failures", mapper.valueToTree(errors));
            return new Result(502, body);
        }
        return new Result(200, Mappings.mainReply(
                Mappings.equifaxResponse(eq.body()), Mappings.experianResponse(ex.body())));
    }

    private CompletableFuture<Outcome> call(String bureau, String url, ObjectNode payload) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> {
                        Integer status = response == null ? null : response.statusCode();
                        String error = failure == null ? null : reason(failure);
                        JsonNode body = Mappings.object();
                        if (failure == null) {
                            if (status < 200 || status >= 300) {
                                error = status == 404 ? "customer not found at " + bureau
                                        : "bureau returned " + status;
                            } else {
                                try {
                                    body = mapper.readTree(response.body());
                                    if (body == null || !body.isObject()) {
                                        error = "invalid JSON object response";
                                    }
                                } catch (Exception invalid) {
                                    error = "invalid JSON response";
                                }
                            }
                        }
                        return outcome(bureau, url, payload, status, started, error, body);
                    });
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(outcome(bureau, url, payload, null,
                    started, reason(failure), Mappings.object()));
        }
    }

    private static Outcome outcome(String bureau, String url, JsonNode payload, Integer status,
                                   long started, String error, JsonNode body) {
        return new Outcome(body, new CallTrace(bureau, url, payload, status,
                Duration.ofNanos(System.nanoTime() - started).toMillis(), error));
    }

    private static String reason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException) {
                return "connection refused";
            }
        }
        return "transport error";
    }
}
