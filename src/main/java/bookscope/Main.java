package bookscope;

import bookscope.tools.Args;
import bookscope.tools.Bench;
import bookscope.tools.AltModel;
import bookscope.tools.Cluster;
import bookscope.tools.CompareDeployments;
import bookscope.tools.DownloadData;
import bookscope.tools.ExperimentE3;
import bookscope.tools.ExperimentE4;
import bookscope.tools.ExperimentRepresentation;
import bookscope.tools.LoadData;
import bookscope.tools.PrepareData;
import bookscope.tools.TimeDeployments;
import bookscope.tools.VerifyCsv;
import com.mongodb.MongoException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Command-line interface: java -jar target/bookscope.jar [--uri URI] [--json] COMMAND ...
 *
 * <p>The deployment is chosen with --uri or $BOOKSCOPE_URI:
 * mongodb://localhost:27100 is the local server (Part I),
 * mongodb://localhost:27200 is the mongos of the sharded deployment (Part II).
 */
public final class Main {
    // Held in a field: java.util.logging only keeps weak references to loggers.
    private static final Logger DRIVER_LOGGER = Logger.getLogger("org.mongodb.driver");
    private static final String USAGE = """
            usage: java -jar target/bookscope.jar [--uri URI] [--json] COMMAND ...

            Services:
              explore (--title TITLE | --id BOOK) [-k N] [--min-shared N]    F1: a book and its connections
              compare USER_A USER_B [--truncate-a N] [--truncate-b N] [-k N]  F2: compare two readers
              recommend USER [--truncate N] [-k N]                           F3: recommend books to a reader
              trace BOOK_A BOOK_B [-k N]                                     F4: connection between two books
              interests TAG_A TAG_B [-k N] [--min-count N] [--adjust-shared]                   F5: links between two tags
              update FILE                                                    F6: import a batch of ratings (jsonl or csv)
              records [--users 1,2] [--books 3,4] [--tags]                   list the stored records behind a result
              info                                                           deployment, selection and load information
              serve [--port 8080]                                            web interface on http://localhost:8080

            Tools:
              download [--output DIR] [--force]
              prepare [--readers FIRST LAST] [--all-readers] [--raw DIR] [--output DIR]
              cluster start local | start sharded [--shards N] | stop all | stop-shard I | start-shard I | status | reset
              load [--uri URI] [--placement range|hashed] [--prepared DIR]
              compare-deployments [--a URI] [--b URI]
              time-deployments [--uris URI,URI,...] [--repeat N] [--output DIR]
              verify-csv --f2 USER_A USER_B --f4 BOOK_A BOOK_B [--check URI]
              e3 [--uri URI] [--output DIR] [--k N] [--truncate N] [--limit N]
              e4 [--uri URI] [--output DIR]
              representation [--uri URI] [--output DIR] [--samples N] [--repeat N] [--no-build]
              bench [--uri URI] [--label NAME] [--shares 1,0.75,0.5,0.25,0] [--requests N] [--repeats N] [--warmup N] [--seed N]
              alt-model [--uri URI] [--requests N] [--repeats N] [--seed N]
            """;

    private Main() {
    }

    private static Document runService(Store store, String command, Args args) throws Exception {
        List<String> p = args.positional;
        switch (command) {
            case "explore":
                return Explore.explore(store, args.get("--title", null), args.getInteger("--id"), args.getInt("-k", 10), args.getInt("--min-shared", 5));
            case "compare":
                return Compare.compare(store, Integer.parseInt(p.get(1)), Integer.parseInt(p.get(2)),
                        args.getInteger("--truncate-a"), args.getInteger("--truncate-b"), args.getInt("-k", 10));
            case "recommend":
                return Recommend.recommend(store, Integer.parseInt(p.get(1)), args.getInt("-k", 10), args.getInteger("--truncate"));
            case "trace":
                return Trace.trace(store, Integer.parseInt(p.get(1)), Integer.parseInt(p.get(2)), args.getInt("-k", 5));
            case "interests":
                return Interests.interests(store, p.get(1), p.get(2), args.getInt("-k", 5), args.getInt("--min-count", Interests.MIN_TAG_COUNT),
                        args.has("--adjust-shared"));
            case "update":
                return Update.update(store, p.get(1));
            case "records":
                return Update.records(store, args.getInts("--users"), args.getInts("--books"), args.has("--tags"));
            case "info":
                Document hello = store.client.getDatabase("admin").runCommand(new Document("hello", 1));
                Document load = store.db.getCollection("meta").find(new Document("_id", "load")).first();
                Document out = new Document("service", "info").append("status", "ok").append("uri", store.uri)
                        .append("deployment", "isdbgrid".equals(hello.getString("msg")) ? "sharded (mongos)" : "single server");
                if (load != null) {
                    load.remove("_id");
                    out.putAll(load);
                }
                return out;
            default:
                throw new IllegalArgumentException(command);
        }
    }

    /** Runs one service and turns every failure into a result with a status (used by the CLI and the web interface). */
    static Document execute(Store store, String command, Args args) {
        long start = System.nanoTime();
        Document result;
        try {
            result = runService(store, command, args);
        } catch (BookScopeException exc) {
            result = new Document("service", command).append("status", "error").append("message", exc.getMessage());
        } catch (MongoException exc) {
            // A missing shard makes mongos fail the whole request: no partial answer is returned.
            String text = String.valueOf(exc.getMessage());
            result = new Document("service", command).append("status", "unavailable")
                    .append("message", "a data-serving instance needed for this request is unreachable; "
                            + "no result is returned because it would be incomplete")
                    .append("error", exc.getClass().getSimpleName() + ": " + text.substring(0, Math.min(300, text.length())));
            store.refresh();
        } catch (Exception exc) {
            result = new Document("service", command).append("status", "error").append("message", String.valueOf(exc.getMessage()));
        }
        result.append("elapsed_s", Util.rnd((System.nanoTime() - start) / 1e9, 3));
        return result;
    }

    private static int service(String command, Args args) {
        boolean json = args.has("--json");
        Document result;
        try (Store store = new Store(args.get("--uri", Store.DEFAULT_URI))) {
            result = execute(store, command, args);
        }
        if (json) {
            System.out.println(result.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()));
        } else {
            render(result, 0);
        }
        String status = result.getString("status");
        return status.equals("ok") || status.equals("ok_with_rejections") ? 0 : 1;
    }

    private static boolean isContainer(Object value) {
        return value instanceof Map || value instanceof Collection;
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof Map<?, ?> m && m.isEmpty()) || (v instanceof Collection<?> c && c.isEmpty());
    }

    /** Short one-line form for a map/list made only of scalars, else null. */
    private static String flat(Object value) {
        if (value instanceof Map<?, ?> map && map.values().stream().noneMatch(Main::isContainer)) {
            return map.entrySet().stream().filter(e -> e.getValue() != null).map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
        }
        if (value instanceof Collection<?> list && list.stream().noneMatch(Main::isContainer)) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return null;
    }

    private static void renderField(String prefix, Object key, Object v, int childIndent) {
        if (isContainer(v) && flat(v) == null) {
            System.out.println(prefix + key + ":");
            render(v, childIndent);
        } else {
            System.out.println(prefix + key + ": " + (isContainer(v) ? flat(v) : v));
        }
    }

    private static void render(Object value, int indent) {
        String pad = "  ".repeat(indent);
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (!isEmpty(v)) {
                    renderField(pad, k, v, indent + 1);
                }
            });
        } else if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && flat(map) != null) {
                    System.out.println(pad + "- " + flat(map));
                } else if (item instanceof Map<?, ?> map) {
                    boolean first = true;
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (!isEmpty(e.getValue())) {
                            renderField(first ? pad + "- " : pad + "  ", e.getKey(), e.getValue(), indent + 2);
                            first = false;
                        }
                    }
                } else {
                    System.out.println(pad + "- " + item);
                }
            }
        } else {
            System.out.println(pad + value);
        }
    }

    public static void main(String[] argv) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        DRIVER_LOGGER.setLevel(Level.OFF);
        Args args;
        try {
            args = new Args(argv);
        } catch (IllegalArgumentException exc) {
            System.err.println(exc.getMessage() + "\n\n" + USAGE);
            System.exit(2);
            return;
        }
        if (args.positional.isEmpty()) {
            System.out.print(USAGE);
            System.exit(2);
        }
        String command = args.positional.get(0);
        Args toolArgs = new Args(argv);
        toolArgs.positional.remove(0);
        int code = 0;
        switch (command) {
            case "download" -> DownloadData.run(args);
            case "prepare" -> PrepareData.run(args);
            case "cluster" -> Cluster.run(toolArgs);
            case "load" -> LoadData.run(args);
            case "compare-deployments" -> code = CompareDeployments.run(args) ? 0 : 1;
            case "verify-csv" -> code = VerifyCsv.run(args) ? 0 : 1;
            case "e3" -> ExperimentE3.run(args);
            case "e4" -> ExperimentE4.run(args);
            case "representation" -> ExperimentRepresentation.run(args);
            case "time-deployments" -> TimeDeployments.run(args);
            case "serve" -> WebServer.run(args);
            case "alt-model" -> AltModel.run(args);
            case "bench" -> Bench.run(args);
            case "explore", "compare", "recommend", "trace", "interests", "update", "records", "info" -> code = service(command, args);
            default -> {
                System.err.println("unknown command " + command + "\n\n" + USAGE);
                code = 2;
            }
        }
        System.exit(code);
    }
}
