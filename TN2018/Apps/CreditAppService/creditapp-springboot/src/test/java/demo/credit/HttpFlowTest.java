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
}
