package demo.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HttpFlowTest {
    MockWebServer eq;
    MockWebServer ex;
    MockMvc mvc;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        eq = new MockWebServer(); ex = new MockWebServer(); eq.start(); ex.start();
        mvc = MockMvcBuilders.standaloneSetup(new CreditController(mapper, eq.url("/").toString(), ex.url("/").toString(), 1000)).build();
    }
    @AfterEach void close() throws Exception { eq.close(); ex.close(); }
    MockResponse json(String value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value); }
    String send(String input, int expected) throws Exception {
        return mvc.perform(post("/creditdetails").contentType("application/json").content(input))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    @Test void exactRequestsAndEmptyExperianSuccess() throws Exception {
        eq.enqueue(json("{\"FICOScore\":800,\"NoOfInquiries\":1,\"Rating\":\"Excellent\"}")); ex.enqueue(json("{}"));
        var result = mapper.readTree(send("{\"SSN\":\"900-11-0001\",\"DOB\":\"\",\"FirstName\":null}", 200));
        assertThat(result.path("ExperianResponse").size()).isZero();
        assertThat(result.has("TransUnionResponse")).isFalse();
        var a = eq.takeRequest(2, TimeUnit.SECONDS); var b = ex.takeRequest(2, TimeUnit.SECONDS);
        assertThat(a.getMethod()).isEqualTo("POST"); assertThat(a.getPath()).isEqualTo("/creditscore");
        assertThat(b.getMethod()).isEqualTo("POST"); assertThat(b.getPath()).isEqualTo("/creditscore?ssn=900-11-0001");
        for (var request : new okhttp3.mockwebserver.RecordedRequest[]{a,b}) {
            assertThat(request.getHeader("Accept")).isEqualTo("application/json");
            assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(request.getHeader("Upgrade")).isNull();
            assertThat(request.getRequestLine()).endsWith("HTTP/1.1");
        }
        assertThat(mapper.readTree(a.getBody().readUtf8())).isEqualTo(mapper.readTree("{\"SSN\":\"900-11-0001\",\"DOB\":\"\"}"));
        assertThat(mapper.readTree(b.getBody().readUtf8())).isEqualTo(mapper.readTree("{\"dob\":\"\",\"firstName\":\"\",\"lastName\":\"\",\"ssn\":\"900-11-0001\"}"));
    }
    @Test void absentAndEmptyQueryParameters() throws Exception {
        for (String input : new String[]{"{}", "{\"SSN\":null}", "{\"SSN\":\"\"}"}) {
            eq.enqueue(json("{}")); ex.enqueue(json("{}")); send(input, 200);
            assertThat(ex.takeRequest().getPath()).isEqualTo(input.contains("\"\"}") ? "/creditscore?ssn=" : "/creditscore");
            eq.takeRequest();
        }
    }
    @Test void concurrentCalls() throws Exception {
        eq.enqueue(json("{}").setBodyDelay(600, TimeUnit.MILLISECONDS));
        ex.enqueue(json("{}").setBodyDelay(600, TimeUnit.MILLISECONDS));
        long start = System.nanoTime(); send("{}", 200);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1100);
    }
    @Test void notFoundNamesEquifaxWithoutPartialResult() throws Exception {
        eq.enqueue(json("{}").setResponseCode(404)); ex.enqueue(json("{}"));
        assertThat(send("{}", 502)).contains("Equifax", "HTTP 404").doesNotContain("ExperianResponse");
    }
    @Test void timeout() throws Exception {
        eq.enqueue(json("{}").setBodyDelay(1500, TimeUnit.MILLISECONDS)); ex.enqueue(json("{}"));
        assertThat(send("{}", 502)).contains("Equifax", "timeout");
    }
    @Test void malformedAndNonObjectProviderBodies() throws Exception {
        eq.enqueue(json("oops")); ex.enqueue(json("[]"));
        assertThat(send("{}", 502)).contains("Equifax", "Experian", "unparseable JSON", "invalid JSON object");
    }
    @Test void connectionFailure() throws Exception {
        eq.shutdown(); ex.enqueue(json("{}")); assertThat(send("{}", 502)).contains("Equifax", "connection error");
    }
    @Test void malformedClientJson() throws Exception { send("{", 400); }
    @Test void nonObjectClientJson() throws Exception {
        for (String input : new String[]{"[]", "\"customer\"", "123", "null"})
            assertThat(mapper.readTree(send(input, 400)).path("error").asText()).isEqualTo("Request body must be a JSON object");
        assertThat(eq.getRequestCount()).isZero(); assertThat(ex.getRequestCount()).isZero();
    }
    @Test void deterministicFailureOrder() throws Exception {
        eq.enqueue(json("{}").setResponseCode(404)); ex.enqueue(json("{}").setResponseCode(500));
        assertThat(send("{}", 502)).isEqualTo("{\"error\":\"Bureau request failed\",\"providers\":{\"Equifax\":\"HTTP 404\",\"Experian\":\"HTTP 500\"}}");
    }
    @Test void standInHelpersUseConfiguredServerSideDestinations() throws Exception {
        eq.enqueue(json("[{\"ssn\":\"900-11-0001\"}]")); ex.enqueue(json("[]"));
        var records = mvc.perform(get("/demo/standins/equifax/records")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(records).get(0).path("ssn").asText()).isEqualTo("900-11-0001");
        mvc.perform(post("/demo/standins/experian/reset")).andExpect(status().isOk());
        var a = eq.takeRequest(); var b = ex.takeRequest();
        assertThat(a.getMethod()).isEqualTo("GET"); assertThat(a.getPath()).isEqualTo("/standin/records");
        assertThat(b.getMethod()).isEqualTo("POST"); assertThat(b.getPath()).isEqualTo("/standin/reset");
        assertThat(a.getHeader("Upgrade")).isNull(); assertThat(b.getHeader("Upgrade")).isNull();
    }
    @Test void unknownStandInIs404WithoutCallingProviders() throws Exception {
        mvc.perform(get("/demo/standins/other/records")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/standins/other/reset")).andExpect(status().isNotFound());
        assertThat(eq.getRequestCount()).isZero(); assertThat(ex.getRequestCount()).isZero();
    }
    @Test void standInHelperFailuresAreJson502() throws Exception {
        for (var response : new MockResponse[]{json("{}").setResponseCode(503), json("broken"), json("[]").setBodyDelay(1500, TimeUnit.MILLISECONDS)}) {
            eq.enqueue(response);
            var body = mvc.perform(get("/demo/standins/equifax/records")).andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).path("providers").has("equifax")).isTrue();
        }
        ex.shutdown();
        var body = mvc.perform(post("/demo/standins/experian/reset")).andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("experian", "connection error");
    }
}
