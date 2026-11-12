package demo.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Mappings {
    private static final String[] INPUT = {"DOB", "FirstName", "LastName", "SSN"};
    private static final String[] EXPERIAN = {"dob", "firstName", "lastName", "ssn"};
    private Mappings() { }

    public static ObjectNode equifaxInput(JsonNode input) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String field : INPUT) {
            if (input.hasNonNull(field)) result.put(field, input.get(field).asText());
        }
        return result;
    }

    public static ObjectNode experianInput(JsonNode input) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < INPUT.length; i++) {
            result.put(EXPERIAN[i], input.hasNonNull(INPUT[i]) ? input.get(INPUT[i]).asText() : "");
        }
        return result;
    }

    public static ObjectNode score(JsonNode input, boolean experian) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        String[] from = experian ? new String[]{"fiCOScore", "noOfInquiries", "rating"}
                : new String[]{"FICOScore", "NoOfInquiries", "Rating"};
        String[] to = {"FICOScore", "NoOfInquiries", "Rating"};
        for (int i = 0; i < from.length; i++) {
            if (input.hasNonNull(from[i])) result.set(to[i], input.get(from[i]));
        }
        return result;
    }

    public static ObjectNode aggregate(JsonNode equifax, JsonNode experian) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("EquifaxResponse", score(equifax, false));
        result.set("ExperianResponse", score(experian, true));
        return result;
    }
}
