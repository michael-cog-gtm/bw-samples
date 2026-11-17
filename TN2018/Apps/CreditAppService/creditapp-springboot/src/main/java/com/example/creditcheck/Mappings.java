package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Mappings {
    private Mappings() { }

    public static ObjectNode equifaxRequest(JsonNode input) {
        return copy(input, new String[]{"SSN", "FirstName", "LastName", "DOB"},
                new String[]{"SSN", "FirstName", "LastName", "DOB"});
    }

    public static ObjectNode experianRequest(JsonNode input) {
        ObjectNode result = object();
        result.put("dob", input.path("DOB").asText(""));
        result.put("firstName", input.path("FirstName").asText(""));
        result.put("lastName", input.path("LastName").asText(""));
        result.put("ssn", input.path("SSN").asText(""));
        return result;
    }

    public static ObjectNode equifaxResponse(JsonNode input) {
        return copy(input, new String[]{"FICOScore", "NoOfInquiries", "Rating"},
                new String[]{"FICOScore", "NoOfInquiries", "Rating"});
    }

    public static ObjectNode experianResponse(JsonNode input) {
        return copy(input, new String[]{"fiCOScore", "noOfInquiries", "rating"},
                new String[]{"fiCOScore", "noOfInquiries", "rating"});
    }

    public static ObjectNode mainReply(JsonNode equifax, JsonNode experian) {
        ObjectNode reply = object();
        reply.set("EquifaxResponse", equifaxResponse(equifax));
        reply.set("ExperianResponse", copy(experian,
                new String[]{"fiCOScore", "noOfInquiries", "rating"},
                new String[]{"FICOScore", "NoOfInquiries", "Rating"}));
        return reply;
    }

    public static ObjectNode lookupEnd(JsonNode record) {
        return copy(record, new String[]{"ficoscore", "rating", "numofpulls"},
                new String[]{"FICOScore", "Rating", "NoOfInquiries"});
    }

    public static ObjectNode lookupUpdate(JsonNode record, String ssn) {
        ObjectNode result = object();
        if (!record.hasNonNull("numofpulls")) {
            result.put("noOfPulls", "");
        } else if (!record.get("numofpulls").canConvertToInt()) {
            throw new IllegalArgumentException("numofpulls must be an integer");
        } else {
            result.put("noOfPulls", Math.addExact(record.get("numofpulls").intValue(), 1));
        }
        result.put("ssn", ssn == null ? "" : ssn);
        return result;
    }

    public static ObjectNode demoReply(JsonNode record) {
        return copy(record, new String[]{"ficoscore", "rating", "numofpulls"},
                new String[]{"fiCOScore", "rating", "noOfInquiries"});
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode copy(JsonNode input, String[] from, String[] to) {
        ObjectNode result = object();
        for (int i = 0; i < from.length; i++) {
            if (input.hasNonNull(from[i])) {
                result.set(to[i], input.get(from[i]).deepCopy());
            }
        }
        return result;
    }
}
