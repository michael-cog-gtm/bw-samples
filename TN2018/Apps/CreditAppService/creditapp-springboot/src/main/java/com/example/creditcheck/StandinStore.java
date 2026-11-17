package com.example.creditcheck;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

@Component
@Profile("standins")
public class StandinStore {
    public static final String EQUIFAX_SELECT = "select * from public.creditscore where ssn like ?";
    public static final String EXPERIAN_SELECT = "SELECT *\n  FROM public.creditscore where ssn like ?\n";
    public static final String UPDATE = "UPDATE creditscore\n  SET numofpulls = ?\n  WHERE ssn like ?";
    private final JdbcTemplate jdbc;

    public StandinStore(@Value("${standins.database-url}") String url) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.setMaxRows(100);
        jdbc.setQueryTimeout(10);
    }

    @PostConstruct
    public void reset() throws IOException {
        jdbc.update("DELETE FROM public.creditscore");
        seed("db/DBDump.csv");
        seed("db/synthetic-customers.csv");
    }

    private void seed(String resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split(",", -1);
                jdbc.update("INSERT INTO public.creditscore VALUES (?, ?, ?, ?, ?, ?, ?)",
                        fields[0], fields[1], fields[2], fields[3], Integer.parseInt(fields[4]),
                        fields[5], Integer.parseInt(fields[6]));
            }
        }
    }

    public ObjectNode equifax(String ssn) {
        List<ObjectNode> rows = query(EQUIFAX_SELECT, ssn);
        if (rows.isEmpty() || rows.getFirst().path("rating").asText("").isEmpty()) {
            return null;
        }
        ObjectNode row = rows.getFirst();
        ObjectNode parameters = Mappings.lookupUpdate(row, ssn);
        if (!parameters.get("noOfPulls").isIntegralNumber()) {
            throw new IllegalArgumentException("empty integer update parameter");
        }
        jdbc.update(UPDATE, parameters.get("noOfPulls").intValue(), ssn);
        return Mappings.lookupEnd(row);
    }

    public ObjectNode experian(String ssn) {
        List<ObjectNode> rows = query(EXPERIAN_SELECT, ssn);
        return rows.isEmpty() ? Mappings.object() : Mappings.demoReply(rows.getFirst());
    }

    public List<ObjectNode> rows() {
        return query("SELECT * FROM public.creditscore", new Object[0]);
    }

    private List<ObjectNode> query(String sql, Object... parameters) {
        return jdbc.query(sql, (rs, index) -> {
            ObjectNode row = Mappings.object();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String field = rs.getMetaData().getColumnLabel(i);
                Object value = rs.getObject(i);
                if (value instanceof Number number) {
                    row.put(field, number.intValue());
                } else if (value != null) {
                    row.put(field, value.toString());
                }
            }
            return row;
        }, parameters);
    }
}
