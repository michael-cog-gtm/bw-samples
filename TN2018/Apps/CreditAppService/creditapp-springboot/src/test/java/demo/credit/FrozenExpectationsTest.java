package demo.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FrozenExpectationsTest {
    @Test void sourceDerivedCases() throws Exception {
        var root = new ObjectMapper().readTree(getClass().getResourceAsStream("/expectations.json"));
        for (var test : root.get("cases")) {
            assertThat(test.get("source").asText()).contains("CreditApp.module/", ":");
            assertThat(Mappings.aggregate(test.get("equifax"), test.get("experian"))).isEqualTo(test.get("expected"));
        }
        assertThat(root.get("notes")).hasSize(4);
    }
}
