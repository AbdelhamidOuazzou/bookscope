package bookscope.tools;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.Document;

/**
 * Start and stop the MongoDB deployments used by BookScope, all on this workstation.
 *
 * <pre>
 *   local    one mongod (port 27100)                      -> mongodb://localhost:27100
 *   sharded  1 config server (27201), N shard servers     -> mongodb://localhost:27200 (mongos)
 *            (27211, 27212, ...), 1 mongos router (27200)
 * </pre>
 *
 * <p>Each mongod has its own dbpath under ~/.bookscope/db/ (or $BOOKSCOPE_DB_ROOT) and a capped
 * WiredTiger cache, so several instances can share one machine. They still share CPU, memory and
 * disk: this is not a cluster of independent machines.
 *
 * <pre>
 *   cluster start local
 *   cluster start sharded --shards 2
 *   cluster stop-shard 1          (simulate losing one data-serving instance)
 *   cluster start-shard 1
 *   cluster status
 *   cluster stop all
 * </pre>
 */
public final class Cluster {
    // Kept outside the project by default: WiredTiger file names are long and Windows limits
    // paths to 260 characters.
    private static final Path DB_ROOT = Path.of(System.getenv().getOrDefault("BOOKSCOPE_DB_ROOT",
            Path.of(System.getProperty("user.home"), ".bookscope", "db").toString()));
    private static final int LOCAL_PORT = 27100;
    private static final int MONGOS_PORT = 27200;
    private static final int CONFIG_PORT = 27201;
    private static final int SHARD_BASE_PORT = 27211;
    private static final String CACHE_GB = "0.5";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private Cluster() {
    }

    private static String binary(String name) {
        for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
            for (String candidate : List.of(name, name + ".exe")) {
                Path path = Path.of(dir, candidate);
                if (Files.isRegularFile(path)) {
                    return path.toString();
                }
            }
        }
        throw new IllegalStateException(name + " not found on PATH");
    }

    private static MongoClient client(int port) {
        return MongoClients.create("mongodb://localhost:" + port + "/?directConnection=true&serverSelectionTimeoutMS=2000");
    }

    private static boolean isUp(int port) {
        try (MongoClient c = client(port)) {
            c.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (MongoException e) {
            return false;
        }
    }

    private static void launch(List<String> command) throws IOException {
        if (WINDOWS) {
            // Start-Process gives the server its own hidden console, so it outlives this program and the terminal.
            String arguments = command.stream().skip(1).map(a -> "\"" + a + "\"").collect(Collectors.joining(" "));
            new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Start-Process -WindowStyle Hidden -FilePath '" + command.get(0) + "' -ArgumentList '" + arguments + "'")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } else {
            new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        }
    }

    private static void spawn(String name, List<String> args, int port) throws IOException, InterruptedException {
        if (isUp(port)) {
            System.out.println(name + ": already running on " + port);
            return;
        }
        Path log = DB_ROOT.resolve(name + ".log");
        Files.createDirectories(DB_ROOT);
        List<String> command = new ArrayList<>(args);
        command.addAll(List.of("--port", String.valueOf(port), "--bind_ip", "127.0.0.1", "--logpath", log.toString(), "--logappend"));
        launch(command);
        for (int i = 0; i < 60; i++) {
            if (isUp(port)) {
                System.out.println(name + ": started on " + port);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(name + ": did not start, see " + log);
    }

    private static void mongod(String name, int port, List<String> extra) throws IOException, InterruptedException {
        Path dbpath = DB_ROOT.resolve(name);
        Files.createDirectories(dbpath);
        List<String> args = new ArrayList<>(List.of(binary("mongod"), "--dbpath", dbpath.toString(), "--wiredTigerCacheSizeGB", CACHE_GB));
        args.addAll(extra);
        spawn(name, args, port);
    }

    private static void initReplset(String name, int port, boolean configsvr) throws InterruptedException {
        try (MongoClient c = client(port)) {
            MongoDatabase admin = c.getDatabase("admin");
            try {
                admin.runCommand(new Document("replSetGetStatus", 1));
            } catch (MongoException notInitiated) {
                Document config = new Document("_id", name).append("members", List.of(new Document("_id", 0).append("host", "localhost:" + port)));
                if (configsvr) {
                    config.append("configsvr", true);
                }
                admin.runCommand(new Document("replSetInitiate", config));
            }
            for (int i = 0; i < 60; i++) {
                if (Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) {
                    return;
                }
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException(name + ": no primary elected");
    }

    private static String shardName(int i) {
        return "shard" + i;
    }

    private static List<String> shardArgs(int i) {
        return List.of("--shardsvr", "--replSet", shardName(i));
    }

    private static void startLocal() throws IOException, InterruptedException {
        mongod("local", LOCAL_PORT, List.of());
        System.out.println("URI: mongodb://localhost:" + LOCAL_PORT);
    }

    private static void startSharded(int nShards) throws IOException, InterruptedException {
        mongod("config", CONFIG_PORT, List.of("--configsvr", "--replSet", "cfg"));
        initReplset("cfg", CONFIG_PORT, true);
        for (int i = 0; i < nShards; i++) {
            mongod(shardName(i), SHARD_BASE_PORT + i, shardArgs(i));
            initReplset(shardName(i), SHARD_BASE_PORT + i, false);
        }
        spawn("mongos", List.of(binary("mongos"), "--configdb", "cfg/localhost:" + CONFIG_PORT), MONGOS_PORT);
        try (MongoClient c = client(MONGOS_PORT)) {
            MongoDatabase admin = c.getDatabase("admin");
            List<String> known = new ArrayList<>();
            for (Document s : admin.runCommand(new Document("listShards", 1)).getList("shards", Document.class)) {
                known.add(s.getString("_id"));
            }
            for (int i = 0; i < nShards; i++) {
                if (!known.contains(shardName(i))) {
                    admin.runCommand(new Document("addShard", shardName(i) + "/localhost:" + (SHARD_BASE_PORT + i)).append("name", shardName(i)));
                    System.out.println(shardName(i) + ": added to the cluster");
                }
            }
        }
        System.out.println("URI: mongodb://localhost:" + MONGOS_PORT);
    }

    private static void shutdown(String name, int port) throws InterruptedException {
        if (!isUp(port)) {
            return;
        }
        try (MongoClient c = client(port)) {
            c.getDatabase("admin").runCommand(new Document("shutdown", 1).append("force", true));
        } catch (MongoException expected) { // the server closes the connection while stopping
        }
        for (int i = 0; i < 40; i++) {
            if (!isUp(port)) {
                System.out.println(name + ": stopped");
                return;
            }
            Thread.sleep(500);
        }
        System.out.println(name + ": still running on " + port);
    }

    private static List<Integer> runningShards() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(DB_ROOT.resolve(shardName(i))) || isUp(SHARD_BASE_PORT + i)) {
                out.add(i);
            }
        }
        return out;
    }

    private static void status() {
        printStatus("local", LOCAL_PORT);
        printStatus("mongos", MONGOS_PORT);
        printStatus("config", CONFIG_PORT);
        for (int i : runningShards()) {
            printStatus(shardName(i), SHARD_BASE_PORT + i);
        }
    }

    private static void printStatus(String name, int port) {
        System.out.printf("%-8s %d  %s%n", name, port, isUp(port) ? "up" : "down");
    }

    public static void run(Args args) throws IOException, InterruptedException {
        String action = args.positional.isEmpty() ? "status" : args.positional.get(0);
        String target = args.positional.size() > 1 ? args.positional.get(1) : "all";
        switch (action) {
            case "status" -> status();
            case "start" -> {
                if (target.equals("local")) {
                    startLocal();
                } else if (target.equals("sharded")) {
                    startSharded(args.getInt("--shards", 2));
                } else {
                    throw new IllegalArgumentException("start local | start sharded");
                }
            }
            case "stop" -> {
                if (target.equals("sharded") || target.equals("all")) {
                    shutdown("mongos", MONGOS_PORT);
                    for (int i : runningShards()) {
                        shutdown(shardName(i), SHARD_BASE_PORT + i);
                    }
                    shutdown("config", CONFIG_PORT);
                }
                if (target.equals("local") || target.equals("all")) {
                    shutdown("local", LOCAL_PORT);
                }
            }
            case "stop-shard" -> shutdown(shardName(Integer.parseInt(target)), SHARD_BASE_PORT + Integer.parseInt(target));
            case "start-shard" -> mongod(shardName(Integer.parseInt(target)), SHARD_BASE_PORT + Integer.parseInt(target), shardArgs(Integer.parseInt(target)));
            case "reset" -> {
                // Removes every dbpath: use it to switch the sharded deployment to another configuration.
                if (isUp(LOCAL_PORT) || isUp(MONGOS_PORT) || isUp(CONFIG_PORT)) {
                    throw new IllegalStateException("stop the deployments first");
                }
                if (Files.exists(DB_ROOT)) {
                    try (Stream<Path> tree = Files.walk(DB_ROOT)) {
                        for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(p);
                        }
                    }
                }
                System.out.println("removed " + DB_ROOT);
            }
            default -> throw new IllegalArgumentException("start | stop | status | stop-shard | start-shard | reset");
        }
    }
}
