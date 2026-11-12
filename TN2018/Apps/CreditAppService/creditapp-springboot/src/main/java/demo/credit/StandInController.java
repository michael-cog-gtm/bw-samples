package demo.credit;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;

@Profile("standin")
@RestController
@CrossOrigin(origins = "${standin.ui-origin:http://localhost:8080}")
public class StandInController {
    private final boolean experian;
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();

    public StandInController(@Value("${standin.bureau:equifax}") String bureau) {
        if (!List.of("equifax", "experian").contains(bureau)) throw new IllegalArgumentException("Unknown stand-in bureau");
        experian = bureau.equals("experian");
        reset();
    }

    @Bean
    Filter syntheticHeader() {
        return (request, response, chain) -> {
            ((jakarta.servlet.http.HttpServletResponse) response).setHeader("X-Stand-In", "synthetic-local");
            chain.doFilter(request, response);
        };
    }

    @PostMapping("/standin/reset")
    public synchronized List<Map<String, Object>> reset() {
        records.clear();
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/customers.csv"), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().skip(1).toList()) {
                String[] fields = line.split(",", -1);
                int offset = experian ? 5 : 2;
                if (fields[offset].isEmpty()) continue;
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("name", fields[0]); record.put("ssn", fields[1]);
                record.put("score", Integer.parseInt(fields[offset])); record.put("rating", fields[offset + 1]);
                record.put("inquiries", Integer.parseInt(fields[offset + 2]));
                records.put(fields[1], record);
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot load synthetic seed", error); }
        return records();
    }

    @GetMapping("/standin/records")
    public synchronized List<Map<String, Object>> records() {
        return records.values().stream().map(record -> (Map<String, Object>) new LinkedHashMap<>(record)).toList();
    }

    @PostMapping("/creditscore")
    public synchronized ResponseEntity<?> score(@RequestBody JsonNode input) {
        Map<String, Object> record = records.get(input.path(experian ? "ssn" : "SSN").asText());
        if (record == null || (!experian && record.get("rating").toString().isEmpty()))
            return experian ? ResponseEntity.ok(Map.of()) : ResponseEntity.status(404).body(Map.of("error", "Record Not Found"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(experian ? "fiCOScore" : "FICOScore", record.get("score"));
        result.put(experian ? "rating" : "Rating", record.get("rating"));
        result.put(experian ? "noOfInquiries" : "NoOfInquiries", record.get("inquiries"));
        if (!experian) record.put("inquiries", (Integer) record.get("inquiries") + 1);
        return ResponseEntity.ok(result);
    }
}
