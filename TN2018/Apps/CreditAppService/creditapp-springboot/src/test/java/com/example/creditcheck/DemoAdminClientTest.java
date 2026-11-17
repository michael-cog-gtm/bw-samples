package com.example.creditcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DemoAdminClientTest {
    @ParameterizedTest
    @CsvSource({"200,[],200", "400,{\"error\":\"invalid mode\"},400", "200,not-json,502", "200,42,502"})
    void forwardsJsonAndHandlesInvalidResponses(int status, String body, int expected) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/rows", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        var client = new DemoAdminClient(new ObjectMapper(), base);
        try {
            var response = client.request("/admin/rows", null);
            assertThat(response.getStatusCode().value()).isEqualTo(expected);
            if (expected != 502) {
                assertThat(response.getBody()).isEqualTo(new ObjectMapper().readTree(body));
            }
        } finally {
            server.stop(0);
        }
        assertThat(client.request("/admin/rows", null).getStatusCode().value()).isEqualTo(502);
        assertThat(client.request("/admin/reset", Mappings.object()).getBody().path("error").asText())
                .contains("Demo controls unavailable");
    }
}
