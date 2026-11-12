package demo.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.assertj.core.api.Assertions.assertThat;

class XsltParityTest {
    static final String CREDIT = "/y54cuadtcxtfstqs3rux2gfdaxppoqgc/T1535409245354Converted/JsonSchema";
    static final String EXP = "http://tns.tibco.com/bw/json/1535671685533";
    final ObjectMapper mapper = new ObjectMapper();
    final Processor processor = new Processor(false);
    final Path originals = Path.of("../CreditApp.module/Processes/creditapp/module");

    Element activity(String file, String name) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(originals.resolve(file + ".bwp").toFile());
        var all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (name.equals(e.getAttribute("name")) && java.util.List.of("extActivity", "activityExtension", "invoke", "reply").contains(e.getLocalName())) return e;
        }
        throw new AssertionError("Missing source activity " + file + "/" + name);
    }

    String stylesheet(String file, String name, boolean whole) throws Exception {
        Element activity = activity(file, name);
        if (whole && activity.hasAttribute("expression")) return activity.getAttribute("expression");
        var nodes = activity.getElementsByTagNameNS("http://www.tibco.com/bpel/2007/extensions", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (!java.util.List.of("inputBinding", "partBinding").contains(e.getLocalName())) continue;
            if (whole && !e.hasAttribute("expression") && !e.getTextContent().isBlank()) return e.getTextContent();
            if (!whole && e.hasAttribute("expression")) return e.getAttribute("expression");
        }
        throw new AssertionError("Missing stylesheet " + file + "/" + name);
    }

    XdmNode xml(String value) throws Exception { return processor.newDocumentBuilder().build(new StreamSource(new StringReader(value))); }
    XdmNode element(String value) throws Exception { return xml(value).children().iterator().next(); }
    XdmNode transform(String file, String activity, boolean whole, Map<String, XdmNode> parameters) throws Exception {
        var transformer = processor.newXsltCompiler().compile(new StreamSource(new StringReader(stylesheet(file, activity, whole)))).load();
        transformer.setInitialContextNode(xml("<root/>"));
        parameters.forEach((name, value) -> transformer.setParameter(new QName(name), value));
        var result = new XdmDestination(); transformer.setDestination(result); transformer.transform(); return result.getXdmNode();
    }

    String node(String root, String namespace, JsonNode values) {
        StringBuilder xml = new StringBuilder("<n:" + root + " xmlns:n=\"" + namespace + "\">");
        values.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) xml.append("<n:").append(entry.getKey()).append(">")
                    .append(escape(entry.getValue().asText())).append("</n:").append(entry.getKey()).append(">");
        });
        return xml.append("</n:").append(root).append(">").toString();
    }
    String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    JsonNode flatten(XdmNode result, String root) throws Exception {
        var xpath = processor.newXPathCompiler().compile("//*[local-name()='" + root + "'][1]").load(); xpath.setContextItem(result);
        XdmNode selected = (XdmNode) xpath.evaluateSingle(); assertThat(selected).as(root).isNotNull();
        return tree(selected);
    }
    ObjectNode tree(XdmNode node) {
        ObjectNode result = mapper.createObjectNode();
        for (XdmNode child : node.children()) {
            if (child.getNodeKind() != XdmNodeKind.ELEMENT) continue;
            String key = child.getNodeName().getLocalName();
            boolean nested = false;
            for (XdmNode c : child.children()) if (c.getNodeKind() == XdmNodeKind.ELEMENT) nested = true;
            if (nested || key.endsWith("Response")) result.set(key, tree(child));
            else if (java.util.List.of("FICOScore", "NoOfInquiries", "fiCOScore", "noOfInquiries").contains(key)) result.put(key, Integer.parseInt(child.getStringValue()));
            else result.put(key, child.getStringValue());
        }
        return result;
    }

    @Test void requestMappingsFullMissingEmptyAndNull() throws Exception {
        for (String fixture : new String[]{"{\"DOB\":\"2000-01-01\",\"FirstName\":\"Synthetic\",\"LastName\":\"Aurora\",\"SSN\":\"900-11-0001\"}", "{}", "{\"SSN\":\"\",\"DOB\":\"\",\"FirstName\":null}"}) {
            JsonNode input = mapper.readTree(fixture);
            var start = element(node("GiveNewSchemaNameHere", CREDIT, input));
            for (String call : new String[]{"EquifaxScore", "ExperianScore"}) {
                assertThat(flatten(transform("MainProcess", call, false, Map.of("post.item", start)), "GiveNewSchemaNameHere")).isEqualTo(Mappings.equifaxInput(input));
                var whole = element("<post><item>" + node("GiveNewSchemaNameHere", CREDIT, input) + "</item></post>");
                assertThat(flatten(transform("MainProcess", call, true, Map.of("post", whole)), "GiveNewSchemaNameHere")).isEqualTo(Mappings.equifaxInput(input));
            }
            for (boolean whole : new boolean[]{false,true})
                assertThat(flatten(transform("EquifaxScore", "post", whole, Map.of("Start", start)), "GiveNewSchemaNameHere")).isEqualTo(Mappings.equifaxInput(input));
            assertThat(flatten(transform("ExperianScore", "RenderJSON", false, Map.of("Start", start)), "InputElement")).isEqualTo(Mappings.experianInput(input));
            var http = flatten(transform("ExperianScore", "SendHTTPRequest", false, Map.of("Start", start,
                    "RenderJSON", element("<render><jsonString>" + escape(Mappings.experianInput(input).toString()) + "</jsonString></render>"))), "RequestActivityInput");
            assertThat(http.path("Method").asText()).isEqualTo("POST");
            assertThat(http.path("RequestURI").asText()).isEqualTo("/creditscore");
            assertThat(mapper.readTree(http.path("PostData").asText())).isEqualTo(Mappings.experianInput(input));
            assertThat(http.path("Headers")).isEqualTo(mapper.readTree("{\"Accept\":\"application/json\",\"Content-Type\":\"application/json\"}"));
            assertThat(http.path("parameters").has("ssn")).isEqualTo(input.hasNonNull("SSN"));
            if (input.hasNonNull("SSN")) assertThat(http.path("parameters").path("ssn").asText()).isEqualTo(input.get("SSN").asText());
        }
    }

    @Test void responseMappingsFullEmptyAndPartial() throws Exception {
        String[][] fixtures = {{"{\"FICOScore\":800,\"NoOfInquiries\":1,\"Rating\":\"Excellent\"}", "{\"fiCOScore\":800,\"noOfInquiries\":2,\"rating\":\"Good\"}"}, {"{}","{}"}, {"{\"Rating\":\"\"}","{\"fiCOScore\":650}"}};
        for (String[] fixture : fixtures) {
            JsonNode eq = mapper.readTree(fixture[0]), ex = mapper.readTree(fixture[1]);
            var eqNode = element(node("SuccessSchema", CREDIT, eq));
            assertThat(flatten(transform("EquifaxScore", "End", false, Map.of("post.item", eqNode)), "SuccessSchema")).isEqualTo(Mappings.score(eq,false));
            assertThat(flatten(transform("EquifaxScore", "End", true, Map.of("post", element("<post><item>" + node("SuccessSchema", CREDIT, eq) + "</item></post>"))), "SuccessSchema")).isEqualTo(Mappings.score(eq,false));
            var exNode = element(node("ExperianResponseSchemaElement", EXP, ex));
            var end = flatten(transform("ExperianScore", "End", false, Map.of("ParseJSON", exNode)), "ExperianResponseSchemaElement");
            assertThat(Mappings.score(end,true)).isEqualTo(Mappings.score(ex,true));
            for (boolean whole : new boolean[]{false,true})
                assertThat(flatten(transform("MainProcess", "postOut", whole, Map.of("EquifaxScore", eqNode, "ExperianScore", exNode)), "CreditScoreSuccessSchema")).isEqualTo(Mappings.aggregate(eq,ex));
        }
    }
}
