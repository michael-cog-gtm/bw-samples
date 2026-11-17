package com.example.creditcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathFactory;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class XsltParityTest {
    static final Path CREDIT = Path.of("../CreditApp.module");
    static final Path BACKEND = Path.of("../../CreditCheckBackendService/CreditCheckService");
    private static final String JSON_NS = "/y54cuadtcxtfstqs3rux2gfdaxppoqgc/T1535409245354Converted/JsonSchema";
    private static final String EXPERIAN_NS = "http://tns.tibco.com/bw/json/1535671685533";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Processor saxon = new Processor(false);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void equifaxRequest(boolean present) throws Exception {
        JsonNode input = input(present);
        Element actual = transform("EquifaxScore", "post", "partBinding", Map.of(
                "Start", xml("GiveNewSchemaNameHere", JSON_NS, input)));
        assertThat(fields(actual)).isEqualTo(Mappings.equifaxRequest(input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void equifaxResponse(boolean present) throws Exception {
        JsonNode input = scores(present);
        Element actual = transform("EquifaxScore", "End", "inputBinding", Map.of(
                "post.item", xml("SuccessSchema", JSON_NS, input)));
        assertThat(fields(actual)).isEqualTo(Mappings.equifaxResponse(input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void experianRequest(boolean present) throws Exception {
        JsonNode input = input(present);
        Element actual = transform("ExperianScore", "RenderJSON", "inputBinding", Map.of(
                "Start", xml("GiveNewSchemaNameHere", JSON_NS, input)));
        assertThat(fields(actual)).isEqualTo(Mappings.experianRequest(input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void experianResponse(boolean present) throws Exception {
        JsonNode input = present ? mapper.readTree("{\"fiCOScore\":0,\"noOfInquiries\":0,\"rating\":\"\"}")
                : Mappings.object();
        Element actual = transform("ExperianScore", "End", "inputBinding", Map.of(
                "ParseJSON", xml("ExperianResponseSchemaElement", EXPERIAN_NS, input)));
        assertThat(fields(actual)).isEqualTo(Mappings.experianResponse(input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mainReply(boolean present) throws Exception {
        JsonNode equifax = scores(present);
        JsonNode experian = present ? mapper.readTree("{\"fiCOScore\":700,\"noOfInquiries\":2,\"rating\":\"Good\"}")
                : Mappings.object();
        Element actual = transform("MainProcess", "postOut", "partBinding", Map.of(
                "EquifaxScore", xml("SuccessSchema", JSON_NS, equifax),
                "ExperianScore", xml("ExperianResponseSchemaElement", EXPERIAN_NS, experian)));
        assertThat(fields(actual)).isEqualTo(Mappings.mainReply(equifax, experian));
        assertThat(fields(actual).has("TransUnionResponse")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void lookupEnd(boolean present) throws Exception {
        JsonNode row = present ? mapper.readTree("{\"ficoscore\":0,\"rating\":\"\",\"numofpulls\":0}")
                : Mappings.object();
        Element actual = transform("LookupDatabase", "End", "inputBinding", Map.of(
                "QueryRecords", record(row, present)));
        assertThat(fields(actual)).isEqualTo(Mappings.lookupEnd(row));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 104})
    void lookupUpdateParameters(int count) throws Exception {
        ObjectNode row = Mappings.object().put("numofpulls", count);
        Element actual = transform("LookupDatabase", "UpdatePulls", "inputBinding", Map.of(
                "QueryRecords", record(row, true), "Start", xml("Element",
                        "http://www.example.com/namespaces/tns/1535845694732",
                        Mappings.object().put("ssn", "123-%"))));
        assertThat(fields(actual)).isEqualTo(Mappings.lookupUpdate(row, "123-%"));
    }

    @Test
    void absentUpdateFieldsProduceEmptyValuesRatherThanInventingACount() throws Exception {
        Element actual = transform("LookupDatabase", "UpdatePulls", "inputBinding", Map.of(
                "QueryRecords", record(Mappings.object(), false), "Start", "<Element/>"));
        assertThat(fields(actual)).isEqualTo(Mappings.lookupUpdate(Mappings.object(), null));
        assertThat(fields(actual).path("noOfPulls").asText()).isEmpty();
    }

    @Test
    void invalidUpdateCountIsRejected() {
        assertThatThrownBy(() -> Mappings.lookupUpdate(Mappings.object().put("numofpulls", "not-an-integer"), "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialFieldsAreOmittedButEmptyAndZeroValuesAreRetained() throws Exception {
        ObjectNode equifax = Mappings.object().put("FICOScore", 0).put("Rating", "");
        ObjectNode experian = Mappings.object().put("noOfInquiries", 0);
        Element actual = transform("MainProcess", "postOut", "partBinding", Map.of(
                "EquifaxScore", xml("SuccessSchema", JSON_NS, equifax),
                "ExperianScore", xml("ExperianResponseSchemaElement", EXPERIAN_NS, experian)));
        assertThat(fields(actual)).isEqualTo(Mappings.mainReply(equifax, experian));
    }

    @Test
    void sqlConstantsAreVerbatimOriginalXmlAttributes() throws Exception {
        Document lookup = read(BACKEND.resolve("Processes/creditcheckservice/LookupDatabase.bwp"));
        Document demo = read(Path.of("../../ExperianDemoService/ExperianService.module/Processes/experianservice/module/Process.bwp"));
        assertThat(attribute(lookup, "QueryRecords", "sqlStatement")).isEqualTo(StandinStore.EQUIFAX_SELECT);
        assertThat(attribute(lookup, "UpdatePulls", "sqlStatement")).isEqualTo(StandinStore.UPDATE);
        assertThat(attribute(demo, "JDBCQuery", "sqlStatement")).isEqualTo(StandinStore.EXPERIAN_SELECT);
    }

    private String attribute(Document document, String activity, String name) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate("//*[@name='" + activity
                + "']//*[@" + name + "]/@" + name, document);
    }

    private JsonNode input(boolean present) throws Exception {
        return present ? mapper.readTree("{\"DOB\":\"01-01-2000\",\"FirstName\":\"A&B\",\"LastName\":\"\",\"SSN\":\"123-%\"}")
                : Mappings.object();
    }

    private JsonNode scores(boolean present) throws Exception {
        return present ? mapper.readTree("{\"FICOScore\":900,\"NoOfInquiries\":104,\"Rating\":\"Excellent\"}")
                : Mappings.object();
    }

    private Element transform(String process, String activity, String binding,
                              Map<String, String> parameters) throws Exception {
        Path file = process.equals("LookupDatabase")
                ? BACKEND.resolve("Processes/creditcheckservice/LookupDatabase.bwp")
                : CREDIT.resolve("Processes/creditapp/module/" + process + ".bwp");
        Document original = read(file);
        String expression = XPathFactory.newInstance().newXPath().evaluate(
                "(//*[@name='" + activity + "']/*[local-name()='inputBindings']/*[local-name()='"
                        + binding + "'])[1]/@expression", original);
        assertThat(expression).as("original stylesheet %s/%s", process, activity).isNotBlank();
        XsltTransformer transform = saxon.newXsltCompiler()
                .compile(new StreamSource(new StringReader(expression))).load();
        transform.setInitialContextNode(saxon.newDocumentBuilder().build(new StreamSource(new StringReader("<context/>"))));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            XdmNode doc = saxon.newDocumentBuilder().build(new StreamSource(new StringReader(parameter.getValue())));
            transform.setParameter(new QName(parameter.getKey()), doc.axisIterator(Axis.CHILD).next());
        }
        StringWriter writer = new StringWriter();
        Serializer serializer = saxon.newSerializer(writer);
        transform.setDestination(serializer);
        transform.transform();
        return parse(writer.toString()).getDocumentElement();
    }

    private String record(JsonNode row, boolean present) {
        return "<resultSet>" + (present ? xml("Record", "", row) : "") + "</resultSet>";
    }

    private String xml(String root, String namespace, JsonNode values) {
        StringBuilder result = new StringBuilder("<" + root + " xmlns=\"" + namespace + "\">");
        values.fields().forEachRemaining(field -> result.append('<').append(field.getKey()).append('>')
                .append(escape(field.getValue().asText())).append("</").append(field.getKey()).append('>'));
        return result.append("</").append(root).append('>').toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static ObjectNode fields(Element element) {
        ObjectNode result = Mappings.object();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String name = child.getLocalName();
                boolean nested = false;
                for (Node node = child.getFirstChild(); node != null; node = node.getNextSibling()) {
                    nested |= node instanceof Element;
                }
                if (nested || name.endsWith("Response")) {
                    result.set(name, fields(child));
                } else if (name.matches("FICOScore|fiCOScore|NoOfInquiries|noOfInquiries|noOfPulls")
                        && !child.getTextContent().isEmpty()) {
                    result.put(name, Integer.parseInt(child.getTextContent()));
                } else {
                    result.put(name, child.getTextContent());
                }
            }
        }
        return result;
    }

    static Document read(Path path) throws Exception {
        return factory().newDocumentBuilder().parse(path.toFile());
    }

    static Document parse(String xml) throws Exception {
        return factory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }
}
