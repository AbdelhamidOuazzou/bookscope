package bookscope;

import bookscope.tools.Args;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Web interface: java -jar target/bookscope.jar serve [--port 8080], then open http://localhost:8080.
 *
 * <p>The page (resource web/index.html) calls the same services as the command line:
 * <ul>
 *   <li>GET  /api/run?uri=URI&amp;argv=compare&amp;argv=32055&amp;argv=36002 runs one read-only service;</li>
 *   <li>POST /api/update?uri=URI with a batch (jsonl or csv) in the body runs F6;</li>
 *   <li>GET  /api/sample-batch returns the first lines of data/prepared/update_batch.jsonl.</li>
 * </ul>
 * The server listens on 127.0.0.1 only and handles one request at a time. One Store (connection and
 * cached catalogue) is kept per deployment URI, so only the first request pays for loading the catalogue.
 */
public final class WebServer {
    private static final Set<String> SERVICES = Set.of("explore", "compare", "recommend", "trace", "interests", "records", "info");
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final Map<String, Store> STORES = new HashMap<>();

    private WebServer() {
    }

    private static Store store(String uri) {
        if (!uri.startsWith("mongodb://localhost:") && !uri.startsWith("mongodb://127.0.0.1:")) {
            throw new BookScopeException("only local deployments are allowed: " + uri);
        }
        return STORES.computeIfAbsent(uri, Store::new);
    }

    private static Map<String, List<String>> query(HttpExchange ex) {
        Map<String, List<String>> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }
        return out;
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendJson(HttpExchange ex, Document doc) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", doc.toJson(JSON).getBytes(StandardCharsets.UTF_8));
    }

    private static Document error(String service, String message) {
        return new Document("service", service).append("status", "error").append("message", message);
    }

    private static void run(HttpExchange ex) throws IOException {
        Map<String, List<String>> q = query(ex);
        List<String> argv = q.getOrDefault("argv", List.of());
        String uri = q.getOrDefault("uri", List.of(Store.DEFAULT_URI)).get(0);
        if (argv.isEmpty() || !SERVICES.contains(argv.get(0))) {
            sendJson(ex, error("?", "unknown service"));
            return;
        }
        Document result;
        try {
            result = Main.execute(store(uri), argv.get(0), new Args(argv.toArray(String[]::new)));
        } catch (RuntimeException exc) {
            result = error(argv.get(0), String.valueOf(exc.getMessage()));
        }
        sendJson(ex, result);
    }

    private static void update(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", new byte[0]);
            return;
        }
        String uri = query(ex).getOrDefault("uri", List.of(Store.DEFAULT_URI)).get(0);
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        Path file = Files.createTempFile("bookscope-batch-", body.startsWith("{") ? ".jsonl" : ".csv");
        Document result;
        try {
            Files.writeString(file, body + "\n", StandardCharsets.UTF_8);
            result = Main.execute(store(uri), "update", new Args(new String[] {"update", file.toString()}));
        } catch (RuntimeException exc) {
            result = error("update", String.valueOf(exc.getMessage()));
        } finally {
            Files.deleteIfExists(file);
        }
        sendJson(ex, result);
    }

    private static void sampleBatch(HttpExchange ex) throws IOException {
        Path path = Path.of("data", "prepared", "update_batch.jsonl");
        String text = "";
        if (Files.exists(path)) {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            text = String.join("\n", lines.subList(0, Math.min(10, lines.size())));
        }
        send(ex, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void page(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    public static void run(Args args) throws IOException, InterruptedException {
        int port = args.getInt("--port", 8080);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", WebServer::page);
        server.createContext("/api/run", WebServer::run);
        server.createContext("/api/update", WebServer::update);
        server.createContext("/api/sample-batch", WebServer::sampleBatch);
        server.start();
        System.out.println("BookScope web interface on http://localhost:" + port + "  (Ctrl-C to stop)");
        Thread.currentThread().join();
    }
}
