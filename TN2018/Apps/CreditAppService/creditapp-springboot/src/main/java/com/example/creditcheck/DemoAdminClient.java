package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@Profile("!standins")
public class DemoAdminClient {
    private final String base;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public DemoAdminClient(ObjectMapper mapper,
                           @Value("${credit.standin-admin-base-url}") String base) {
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
    }

    public ResponseEntity<JsonNode> request(String path, JsonNode body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json");
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(response.body());
            if (result == null || (!result.isObject() && !result.isArray())) {
                return unavailable();
            }
            return ResponseEntity.status(response.statusCode()).body(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (Exception unavailable) {
            return unavailable();
        }
    }

    private ResponseEntity<JsonNode> unavailable() {
        return ResponseEntity.status(502).body(Mappings.object().put("error",
                "Demo controls unavailable. Start the local stand-ins and try again."));
    }
}
