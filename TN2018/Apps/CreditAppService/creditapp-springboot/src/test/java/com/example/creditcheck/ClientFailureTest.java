package com.example.creditcheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientFailureTest {
    @ParameterizedTest
    @CsvSource({"503,{},bureau returned 503", "200,not-json,invalid JSON response", "200,[],invalid JSON object response"})
    void otherStatusesAndMalformedResponsesFailWholeRequest(int status, String body, String reason) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creditscore", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            CreditService service = new CreditService(new ObjectMapper(), base, base, 1000);
            var result = service.check(Mappings.object());
            assertThat(result.status()).isEqualTo(502);
            assertThat(result.body().path("failures")).hasSize(2);
            assertThat(result.body().at("/failures/0/reason").asText()).isEqualTo(reason);
            assertThat(service.lastTrace().calls().getFirst().status()).isEqualTo(status);
            assertThat(service.lastTrace().calls().getFirst().responseBody()).isEqualTo(body);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulEmptyObjectsOmitAbsentFieldsAndEncodeSsnQuery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/creditscore", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort() + "/";
            CreditService service = new CreditService(new ObjectMapper(), base, base, 1000);
            var result = service.check(Mappings.object().put("SSN", "a b&%"));
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(new ObjectMapper().readTree("{\"EquifaxResponse\":{},\"ExperianResponse\":{}}"));
            assertThat(service.lastTrace().calls().get(1).url()).endsWith("/creditscore?ssn=a+b%26%25");
        } finally {
            server.stop(0);
        }
    }
}
