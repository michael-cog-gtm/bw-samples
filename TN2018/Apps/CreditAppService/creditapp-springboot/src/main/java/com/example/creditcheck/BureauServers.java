package com.example.creditcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("standins")
public class BureauServers {
    public enum Bureau { EQUIFAX, EXPERIAN }
    public enum Mode { ONLINE, DOWN, SLOW }
    public record State(Mode mode, long delayMs, int port) { }
    private static final Logger LOG = LoggerFactory.getLogger(BureauServers.class);
    private final StandinStore store;
    private final ObjectMapper mapper;
    private final Map<Bureau, Server> servers = new EnumMap<>(Bureau.class);
    private final long slowMs;
    private final Path baseDirectory;

    private static final class Server {
        private final Tomcat tomcat = new Tomcat();
        private volatile Mode mode = Mode.ONLINE;
        private volatile long delayMs;
        private Connector connector;
    }

    public BureauServers(StandinStore store, ObjectMapper mapper,
                         @Value("${standins.equifax-port:13080}") int equifaxPort,
                         @Value("${standins.experian-port:7080}") int experianPort,
                         @Value("${standins.slow-ms:1600}") long slowMs,
                         @Value("${standins.work-directory:.run/tomcat}") String workDirectory) {
        this.store = store;
        this.mapper = mapper;
        this.slowMs = slowMs;
        this.baseDirectory = Path.of(workDirectory).toAbsolutePath();
        configure(Bureau.EQUIFAX, equifaxPort);
        configure(Bureau.EXPERIAN, experianPort);
    }

    private void configure(Bureau bureau, int port) {
        Server server = new Server();
        server.tomcat.setPort(port);
        server.connector = server.tomcat.getConnector();
        server.connector.setProperty("address", "127.0.0.1");
        server.connector.setProperty("bindOnInit", "false");
        servers.put(bureau, server);
    }

    @PostConstruct
    public void start() throws IOException, LifecycleException {
        for (Map.Entry<Bureau, Server> entry : servers.entrySet()) {
            Bureau bureau = entry.getKey();
            Server server = entry.getValue();
            Path directory = Files.createDirectories(baseDirectory.resolve(bureau.name().toLowerCase()));
            server.tomcat.setBaseDir(directory.toString());
            Context context = server.tomcat.addContext("", directory.toString());
            Tomcat.addServlet(context, "creditscore", new HttpServlet() {
                @Override
                protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    serve(bureau, server, request, response);
                }
            });
            context.addServletMappingDecoded("/creditscore", "creditscore");
            server.tomcat.start();
            server.connector.setPort(server.connector.getLocalPort());
        }
    }

    private void serve(Bureau bureau, Server server, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        try {
            Thread.sleep(server.delayMs);
            JsonNode input;
            try {
                input = mapper.readTree(request.getInputStream());
            } catch (IOException invalid) {
                response.setStatus(400);
                return;
            }
            if (input == null || !input.isObject()) {
                response.setStatus(400);
                return;
            }
            String key = bureau == Bureau.EQUIFAX ? "SSN" : "ssn";
            String ssn = input.hasNonNull(key) ? input.get(key).asText() : null;
            ObjectNode body = bureau == Bureau.EQUIFAX ? store.equifax(ssn) : store.experian(ssn);
            if (body == null) {
                LOG.info("Invocation Failed");
                response.setStatus(404);
            } else {
                if (bureau == Bureau.EQUIFAX) {
                    LOG.info("Invoation Successful");
                }
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), body);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
        } catch (RuntimeException failure) {
            if (bureau == Bureau.EQUIFAX) {
                LOG.info("Invocation Failed");
                response.setStatus(404);
            } else {
                response.setStatus(500);
            }
        }
    }

    public synchronized State mode(Bureau bureau, Mode mode, Long delayMs) throws LifecycleException {
        if (delayMs != null && (delayMs < 0 || delayMs > 60_000)) {
            throw new IllegalArgumentException("delayMs must be between 0 and 60000");
        }
        Server server = servers.get(bureau);
        if (mode == Mode.DOWN && server.mode != Mode.DOWN) {
            server.connector.stop();
        } else if (mode != Mode.DOWN && server.mode == Mode.DOWN) {
            server.connector.start();
        }
        server.delayMs = mode == Mode.SLOW ? (delayMs == null ? slowMs : delayMs) : 0;
        server.mode = mode;
        return state(server);
    }

    public synchronized Map<Bureau, State> states() {
        Map<Bureau, State> result = new EnumMap<>(Bureau.class);
        servers.forEach((bureau, server) -> result.put(bureau, state(server)));
        return result;
    }

    private State state(Server server) {
        return new State(server.mode, server.delayMs, server.connector.getLocalPort());
    }

    public synchronized void reset() throws IOException, LifecycleException {
        for (Server server : servers.values()) {
            server.connector.stop();
        }
        store.reset();
        for (Server server : servers.values()) {
            server.delayMs = 0;
            server.connector.start();
            server.mode = Mode.ONLINE;
        }
    }

    @PreDestroy
    public void stop() throws LifecycleException {
        for (Server server : servers.values()) {
            server.tomcat.stop();
            server.tomcat.destroy();
        }
    }
}
