package demo.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

class StandInTest {
    ObjectMapper mapper = new ObjectMapper();
    @Test void equifaxPreIncrementAndReset() throws Exception {
        var controller = new StandInController("equifax");
        var input = mapper.readTree("{\"SSN\":\"900-11-0001\"}");
        assertThat(((Map<?, ?>) controller.score(input).getBody()).get("NoOfInquiries")).isEqualTo(1);
        assertThat(((Map<?, ?>) controller.score(input).getBody()).get("NoOfInquiries")).isEqualTo(2);
        controller.reset();
        assertThat(((Map<?, ?>) controller.score(input).getBody()).get("NoOfInquiries")).isEqualTo(1);
    }
    @Test void unknownAndEmptyRating() throws Exception {
        var controller = new StandInController("equifax");
        assertThat(controller.score(mapper.readTree("{\"SSN\":\"unknown\"}")).getStatusCode().value()).isEqualTo(404);
        var field = StandInController.class.getDeclaredField("records"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var records = (Map<String, Map<String, Object>>) field.get(controller);
        records.get("900-11-0001").put("rating", "");
        assertThat(controller.score(mapper.readTree("{\"SSN\":\"900-11-0001\"}")).getStatusCode().value()).isEqualTo(404);
    }
    @Test void experianBodyLookupNoIncrementAndUnknown() throws Exception {
        var controller = new StandInController("experian");
        var input = mapper.readTree("{\"ssn\":\"900-11-0001\"}");
        assertThat(controller.score(input).getBody()).isEqualTo(controller.score(input).getBody());
        assertThat(((Map<?, ?>) controller.score(input).getBody()).get("noOfInquiries")).isEqualTo(2);
        assertThat(controller.score(mapper.readTree("{}")).getBody()).isEqualTo(Map.of());
    }
    @Test void syntheticHeaderAndCors() throws Exception {
        var controller = new StandInController("equifax");
        MockMvcBuilders.standaloneSetup(controller).addFilters(controller.syntheticHeader()).build()
                .perform(get("/standin/records").header("Origin", "http://localhost:8080"))
                .andExpect(header().string("X-Stand-In", "synthetic-local"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }
}
