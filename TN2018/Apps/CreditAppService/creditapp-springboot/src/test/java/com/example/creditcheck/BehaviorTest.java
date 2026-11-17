package com.example.creditcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class BehaviorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();
    private ServletWebServerApplicationContext standins;
    private ServletWebServerApplicationContext credit;
    private String admin;
    private String app;
    private String equifax;
    private String experian;
    private int equifaxPort;
    private int experianPort;

    @BeforeAll
    void startApplications() throws Exception {
        standins = (ServletWebServerApplicationContext) new SpringApplicationBuilder(CreditApplication.class)
                .profiles("standins").run("--server.port=0", "--standins.equifax-port=0",
                        "--standins.experian-port=0", "--standins.database-url=jdbc:h2:mem:test-"
                                + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "--standins.work-directory=target/test-tomcat");
        admin = "http://localhost:" + standins.getWebServer().getPort();
        var states = standins.getBean(BureauServers.class).states();
        equifaxPort = states.get(BureauServers.Bureau.EQUIFAX).port();
        experianPort = states.get(BureauServers.Bureau.EXPERIAN).port();
        equifax = "http://localhost:" + equifaxPort;
        experian = "http://localhost:" + experianPort;
        credit = (ServletWebServerApplicationContext) new SpringApplicationBuilder(CreditApplication.class)
                .profiles("credit-test").run("--server.port=0", "--credit.equifax-base-url=" + equifax,
                        "--credit.experian-base-url=" + experian, "--credit.standin-admin-base-url=" + admin);
        app = "http://localhost:" + credit.getWebServer().getPort();
        assertThat(post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}").statusCode()).isEqualTo(200);
    }

    @BeforeEach
    void reset() throws Exception {
        assertThat(post(admin + "/admin/reset", "{}").statusCode()).isEqualTo(200);
    }

    @AfterAll
    void closeApplications() {
        if (credit != null) {
            credit.close();
        }
        if (standins != null) {
            standins.close();
        }
    }

    @Test
    void demoPageAndAssetsAreServedLocally() throws Exception {
        var page = get(app + "/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Credit check", "/demo.js", "/demo.css").doesNotContain("https://");
        assertThat(get(app + "/demo.js").statusCode()).isEqualTo(200);
        assertThat(get(app + "/demo.css").statusCode()).isEqualTo(200);
    }

    @Test
    void demoProxySupportsRowsModesFailuresAndReset() throws Exception {
        JsonNode before = json(get(app + "/api/demo/rows"));
        assertThat(before).hasSize(5);
        assertThat(json(get(app + "/api/demo/bureaus")).at("/EQUIFAX/mode").asText()).isEqualTo("ONLINE");
        assertThat(json(post(app + "/api/demo/bureaus/EQUIFAX", "{\"mode\":\"DOWN\"}"))
                .path("mode").asText()).isEqualTo("DOWN");
        var failed = post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}");
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(json(failed).at("/failures/0/reason").asText()).isEqualTo("connection refused");
        String id = failed.headers().firstValue("X-Credit-Trace-Id").orElseThrow();
        assertThat(json(get(app + "/api/traces/" + id)).at("/calls/0/error").asText()).isEqualTo("connection refused");
        assertThat(post(app + "/api/demo/bureaus/EQUIFAX", "{\"mode\":\"SLOW\"}").statusCode()).isEqualTo(200);
        var slow = post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}");
        assertThat(json(slow).at("/failures/0/reason").asText()).isEqualTo("timeout");
        assertThat(post(app + "/api/demo/reset", "{}").statusCode()).isEqualTo(200);
        assertThat(json(get(app + "/api/demo/rows"))).isEqualTo(before);
        var success = post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}");
        assertThat(success.statusCode()).isEqualTo(200);
        String successId = success.headers().firstValue("X-Credit-Trace-Id").orElseThrow();
        assertThat(successId).isNotEqualTo(id);
        JsonNode trace = json(get(app + "/api/traces/" + successId));
        assertThat(trace.path("id").asText()).isEqualTo(successId);
        assertThat(trace.at("/calls/0/status").asInt()).isEqualTo(200);
        assertThat(trace.at("/calls/0/requestPayload/SSN").asText()).isEqualTo("123-45-6789");
        assertThat(row(json(get(app + "/api/demo/rows")), "123-45-6789").path("numofpulls").asInt())
                .isEqualTo(row(before, "123-45-6789").path("numofpulls").asInt() + 1);
        assertThat(json(get(app + "/api/traces/" + id)).at("/calls/0/error").asText()).isEqualTo("connection refused");
        assertThat(get(app + "/api/traces/not-a-request").statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mode\":\"UNKNOWN\"}", "{\"mode\":\"SLOW\",\"delayMs\":-1}"})
    void demoProxyRejectsInvalidControls(String body) throws Exception {
        assertThat(post(app + "/api/demo/bureaus/EQUIFAX", body).statusCode()).isEqualTo(400);
        assertThat(post(app + "/api/demo/bureaus/UNKNOWN", "{\"mode\":\"DOWN\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void exactOriginalSeedsPlusLabelledSyntheticCustomer() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/db/DBDump.csv")))
                .isEqualTo(Files.readString(Path.of("../../Database/DBDump.csv")));
        JsonNode rows = json(get(admin + "/admin/rows"));
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).path("firstname").asText()).isEqualTo("Vineet");
        assertThat(rows.get(0).path("dateofBirth").asText()).isEqualTo("01/01/2000");
        assertThat(row(rows, "SYN-EMPTY").path("firstname").asText()).isEqualTo("Synthetic");
    }

    @Test
    void equifaxReturnsPreUpdateCountAndIncrements(CapturedOutput output) throws Exception {
        JsonNode response = json(post(equifax + "/creditscore", "{\"SSN\":\"123-45-6789\",\"FirstName\":\"wrong name\"}"));
        assertThat(response).isEqualTo(mapper.readTree("{\"FICOScore\":900,\"Rating\":\"Excellent\",\"NoOfInquiries\":104}"));
        assertThat(row(json(get(admin + "/admin/rows")), "123-45-6789").path("numofpulls").asInt()).isEqualTo(105);
        assertThat(output).contains("Invoation Successful");
    }

    @Test
    void likeUsesFirstRowCountToUpdateAllMatchingRows() throws Exception {
        JsonNode before = json(get(admin + "/admin/rows"));
        int firstCount = before.get(0).path("numofpulls").asInt();
        JsonNode response = json(post(equifax + "/creditscore", "{\"SSN\":\"%\"}"));
        assertThat(response.path("NoOfInquiries").asInt()).isEqualTo(firstCount);
        for (JsonNode row : json(get(admin + "/admin/rows"))) {
            assertThat(row.path("numofpulls").asInt()).isEqualTo(firstCount + 1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "SYN-EMPTY"})
    void equifaxMissingRowOrEmptyRatingIs404WithoutBody(String ssn, CapturedOutput output) throws Exception {
        JsonNode before = json(get(admin + "/admin/rows"));
        HttpResponse<String> response = post(equifax + "/creditscore", mapper.writeValueAsString(Mappings.object().put("SSN", ssn)));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(json(get(admin + "/admin/rows"))).isEqualTo(before);
        assertThat(output).contains("Invocation Failed");
    }

    @Test
    void experianReadsSameTableWithLikeAndDoesNotUpdate() throws Exception {
        JsonNode before = json(get(admin + "/admin/rows"));
        JsonNode response = json(post(experian + "/creditscore", "{\"ssn\":\"012-%\"}"));
        assertThat(response).isEqualTo(mapper.readTree("{\"fiCOScore\":600,\"rating\":\"Good\",\"noOfInquiries\":1}"));
        assertThat(json(get(admin + "/admin/rows"))).isEqualTo(before);
        post(equifax + "/creditscore", "{\"SSN\":\"012-34-5678\"}");
        assertThat(json(post(experian + "/creditscore", "{\"ssn\":\"012-34-5678\"}")).path("noOfInquiries").asInt()).isEqualTo(2);
    }

    @Test
    void experianMissingRowIs200EmptyObjectAndEmptyRatingIsRetained() throws Exception {
        HttpResponse<String> missing = post(experian + "/creditscore", "{\"ssn\":\"missing\"}");
        assertThat(missing.statusCode()).isEqualTo(200);
        assertThat(missing.body()).isEqualTo("{}");
        assertThat(json(post(experian + "/creditscore", "{\"ssn\":\"SYN-EMPTY\"}")).path("rating").asText()).isEmpty();
    }

    @Test
    void creditResponseAndTraceKeepContractsSeparate() throws Exception {
        ObjectNode input = Mappings.object().put("DOB", "01/01/2000").put("FirstName", "Aditya")
                .put("LastName", "Wagle").put("SSN", "123-45-6789");
        HttpResponse<String> response = post(app + "/creditdetails", mapper.writeValueAsString(input));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("EquifaxResponse", "ExperianResponse");
        assertThat(body.path("EquifaxResponse").path("FICOScore").asInt()).isEqualTo(900);
        assertThat(body.path("EquifaxResponse").path("NoOfInquiries").asInt()).isEqualTo(104);
        assertThat(body.path("ExperianResponse").path("NoOfInquiries").asInt()).isIn(104, 105);
        assertThat(body.path("ExperianResponse").path("Rating").asText()).isEqualTo("Excellent");
        JsonNode trace = json(get(app + "/api/last-trace"));
        assertThat(trace.path("id").asText()).isNotBlank();
        assertThat(trace.path("calls")).hasSize(2);
        assertThat(trace.at("/calls/0/requestPayload")).isEqualTo(input);
        assertThat(trace.at("/calls/1/requestPayload")).isEqualTo(Mappings.experianRequest(input));
        assertThat(trace.at("/calls/0/url").asText()).isEqualTo(equifax + "/creditscore");
        assertThat(trace.at("/calls/1/url").asText()).isEqualTo(experian + "/creditscore?ssn=123-45-6789");
        for (JsonNode call : trace.path("calls")) {
            assertThat(call.path("status").asInt()).isEqualTo(200);
            assertThat(call.path("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(call.path("error").isNull()).isTrue();
        }
    }

    @Test
    void slowishCallsRunInParallel() throws Exception {
        mode("EQUIFAX", "SLOW", 600);
        mode("EXPERIAN", "SLOW", 600);
        long started = System.nanoTime();
        HttpResponse<String> result = post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(elapsedMs).as("parallel 600 ms bureau calls, end to end").isBetween(550L, 1099L);
        JsonNode trace = json(get(app + "/api/last-trace"));
        assertThat(trace.at("/calls/0/latencyMs").asLong()).isGreaterThanOrEqualTo(550);
        assertThat(trace.at("/calls/1/latencyMs").asLong()).isGreaterThanOrEqualTo(550);
    }

    @ParameterizedTest
    @CsvSource({"EQUIFAX,Equifax", "EXPERIAN,Experian"})
    void downReleasesActualTcpPortAndWholeRequestFails(String bureau, String label) throws Exception {
        mode(bureau, "DOWN", null);
        int port = bureau.equals("EQUIFAX") ? equifaxPort : experianPort;
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            }
        }).isInstanceOf(ConnectException.class);
        assertThat(get(admin + "/admin/rows").statusCode()).isEqualTo(200);
        assertFailure(post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}"), label, "connection refused");
        JsonNode trace = json(get(app + "/api/last-trace"));
        JsonNode failedCall = trace.path("calls").get(bureau.equals("EQUIFAX") ? 0 : 1);
        assertThat(failedCall.path("status").isNull()).isTrue();
        assertThat(failedCall.path("error").asText()).isEqualTo("connection refused");
        mode(bureau, "ONLINE", null);
        assertThat(post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"EQUIFAX,Equifax", "EXPERIAN,Experian"})
    void slowExceedsPerCallDeadlineAndWholeRequestFails(String bureau, String label) throws Exception {
        mode(bureau, "SLOW", null);
        long started = System.nanoTime();
        assertFailure(post(app + "/creditdetails", "{\"SSN\":\"123-45-6789\"}"), label, "timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(900L, 1500L);
        JsonNode calls = json(get(app + "/api/last-trace")).path("calls");
        assertThat(calls.get(bureau.equals("EQUIFAX") ? 0 : 1).path("error").asText()).isEqualTo("timeout");
    }

    @Test
    void bothFailuresAreReportedWithoutPartialResponse() throws Exception {
        mode("EQUIFAX", "DOWN", null);
        mode("EXPERIAN", "DOWN", null);
        HttpResponse<String> response = post(app + "/creditdetails", "{}");
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response).path("failures")).hasSize(2);
        assertThat(json(response).has("EquifaxResponse")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "SYN-EMPTY"})
    void equifax404BecomesExplicit502Choice(String ssn) throws Exception {
        assertFailure(post(app + "/creditdetails", mapper.writeValueAsString(Mappings.object().put("SSN", ssn))),
                "Equifax", "customer not found at Equifax");
        assertThat(json(get(app + "/api/last-trace")).at("/calls/0/status").asInt()).isEqualTo(404);
    }

    @Test
    void resetReseedsAndRestartsBothBureaus() throws Exception {
        post(equifax + "/creditscore", "{\"SSN\":\"123-45-6789\"}");
        mode("EQUIFAX", "DOWN", null);
        mode("EXPERIAN", "SLOW", null);
        JsonNode state = json(post(admin + "/admin/reset", "{}"));
        assertThat(state.at("/EQUIFAX/mode").asText()).isEqualTo("ONLINE");
        assertThat(state.at("/EXPERIAN/mode").asText()).isEqualTo("ONLINE");
        assertThat(state.at("/EXPERIAN/delayMs").asInt()).isZero();
        assertThat(json(post(experian + "/creditscore", "{\"ssn\":\"123-45-6789\"}")).path("noOfInquiries").asInt()).isEqualTo(104);
        assertThat(post(equifax + "/creditscore", "{\"SSN\":\"123-45-6789\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void emptyOptionalRequestIsAcceptedButNotFoundAndMalformedInputIs400() throws Exception {
        assertFailure(post(app + "/creditdetails", "{}"), "Equifax", "customer not found at Equifax");
        assertThat(post(app + "/creditdetails", "[]").statusCode()).isEqualTo(400);
        assertThat(post(app + "/creditdetails", "not-json").statusCode()).isEqualTo(400);
        assertThat(post(admin + "/admin/bureaus/EQUIFAX", "{}").statusCode()).isEqualTo(400);
        assertThat(post(admin + "/admin/bureaus/EQUIFAX", "{\"mode\":\"SLOW\",\"delayMs\":-1}").statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEST-FICOScore-700-1-Excellent", "TEST-FICOScore-800-1-Excellent", "TEST-Experian-Score-2-Good"})
    void legacyBwtInputsHaveHonestSeedOutcomes(String name) throws Exception {
        Document original = XsltParityTest.read(XsltParityTest.CREDIT.resolve("Tests/" + name + ".bwt"));
        String inputXml = ((Element) original.getElementsByTagName("resolvedInput").item(0)).getAttribute("inputValue");
        ObjectNode input = XsltParityTest.fields(XsltParityTest.parse(inputXml).getDocumentElement());
        if (name.contains("800")) {
            assertThat(input).isEqualTo(mapper.readTree("{\"DOB\":\"01-01-2000\",\"FirstName\":\"John\",\"LastName\":\"Doe\",\"SSN\":\"123-45-6789\"}"));
            JsonNode result = json(post(app + "/creditdetails", mapper.writeValueAsString(input)));
            assertThat(result.at("/EquifaxResponse/FICOScore").asInt()).isEqualTo(900);
            assertThat(result.at("/EquifaxResponse/NoOfInquiries").asInt()).isEqualTo(104);
            assertThat(original.getElementsByTagName("Expression").item(0).getTextContent())
                    .contains("'$End-input/tns3:Rating'='Excellent'");
        } else {
            ObjectNode expected = name.contains("700")
                    ? Mappings.object().put("DOB", "").put("FirstName", "").put("LastName", "").put("SSN", "")
                    : Mappings.object().put("DOB", "01-01-2018").put("FirstName", "John").put("LastName", "Doe").put("SSN", "123-45-9389");
            assertThat(input).isEqualTo(expected);
            assertFailure(post(app + "/creditdetails", mapper.writeValueAsString(input)), "Equifax", "customer not found at Equifax");
            assertThat(json(post(experian + "/creditscore", mapper.writeValueAsString(Mappings.experianRequest(input)))))
                    .isEqualTo(Mappings.object());
            if (name.contains("700")) {
                assertThat(original.getElementsByTagName("Expression").item(0).getTextContent())
                        .contains("$post.item/tns3:SuccessSchema/tns3:FICOScore='800'");
            } else {
                assertThat(original.getElementsByTagName("Expression").item(0).getTextContent())
                        .contains("'$End-input/tns3:rating'='Good'");
            }
        }
        if (name.contains("FICOScore")) {
            assertThat(((Element) original.getElementsByTagName("ProcessNode").item(0)).getAttribute("Name"))
                    .isEqualTo("creditapp.module.FICOScore");
            assertThat(XsltParityTest.CREDIT.resolve("Processes/creditapp/module/FICOScore.bwp")).doesNotExist();
        }
    }

    private void mode(String bureau, String mode, Integer delay) throws Exception {
        ObjectNode request = Mappings.object().put("mode", mode);
        if (delay != null) {
            request.put("delayMs", delay);
        }
        assertThat(post(admin + "/admin/bureaus/" + bureau, mapper.writeValueAsString(request)).statusCode()).isEqualTo(200);
    }

    private void assertFailure(HttpResponse<String> response, String bureau, String reason) throws Exception {
        assertThat(response.statusCode()).isEqualTo(502);
        JsonNode body = json(response);
        assertThat(body.path("failures")).hasSize(1);
        assertThat(body.at("/failures/0/bureau").asText()).isEqualTo(bureau);
        assertThat(body.at("/failures/0/reason").asText()).isEqualTo(reason);
        assertThat(body.has("EquifaxResponse")).isFalse();
        assertThat(body.has("ExperianResponse")).isFalse();
    }

    private JsonNode row(JsonNode rows, String ssn) {
        for (JsonNode row : rows) {
            if (row.path("ssn").asText().equals(ssn)) {
                return row;
            }
        }
        throw new AssertionError("row not found: " + ssn);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
