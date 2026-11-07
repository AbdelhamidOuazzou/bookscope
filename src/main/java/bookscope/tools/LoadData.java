package bookscope.tools;

import static bookscope.Util.toInt;

import bookscope.Store;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.bson.Document;
import org.bson.types.MinKey;

/**
 * Load the prepared JSON Lines files into a BookScope deployment.
 *
 * <pre>
 *   java -jar target/bookscope.jar load --uri mongodb://localhost:27100                      (local server)
 *   java -jar target/bookscope.jar load --uri mongodb://localhost:27200 --placement range    (via mongos)
 *   java -jar target/bookscope.jar load --uri mongodb://localhost:27200 --placement hashed   (alternative)
 * </pre>
 *
 * <p>Placement (sharded deployment only). Reader activity (ratings, to_read, user_stats) is
 * partitioned by user_id so that a reader's whole history lives on one instance:
 * range = contiguous user_id ranges, split points chosen so that every shard holds about the same
 * number of ratings, with the balancer stopped to keep the placement fixed;
 * hashed = hashed user_id, chunks spread and balanced by MongoDB.
 * The catalogue (books, tags, book_stats) is small and is stored unsharded.
 */
public final class LoadData {
    private static final int BATCH = 20000;

    private LoadData() {
    }

    private static long insert(MongoCollection<Document> coll, Path path, boolean pairId) throws IOException {
        InsertManyOptions unordered = new InsertManyOptions().ordered(false);
        List<Document> batch = new ArrayList<>();
        long n = 0;
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Document doc = Document.parse(line);
                batch.add(pairId ? new Document("_id", doc.get("user_id") + ":" + doc.get("book_id")).append("user_id", doc.get("user_id"))
                        .append("book_id", doc.get("book_id")).append("rating", doc.get("rating")).append("seq", doc.get("seq")) : doc);
                if (batch.size() == BATCH) {
                    coll.insertMany(batch, unordered);
                    n += batch.size();
                    batch = new ArrayList<>();
                }
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch, unordered);
            n += batch.size();
        }
        return n;
    }

    /** user_id boundaries giving each shard about the same number of ratings. */
    private static List<Integer> splitPoints(Path prepared, int nShards) throws IOException {
        Map<Integer, Integer> perUser = new TreeMap<>();
        long[] total = {0};
        try (BufferedReader in = Files.newBufferedReader(prepared.resolve("ratings.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                perUser.merge(toInt(Document.parse(line).get("user_id")), 1, Integer::sum);
                total[0]++;
            }
        }
        List<Integer> points = new ArrayList<>();
        long cum = 0;
        for (Map.Entry<Integer, Integer> e : perUser.entrySet()) {
            if (points.size() < nShards - 1 && cum >= (double) total[0] * (points.size() + 1) / nShards) {
                points.add(e.getKey());
            }
            cum += e.getValue();
        }
        return points;
    }

    private static Document shardCollections(MongoClient client, String dbName, Path prepared, String placement) throws IOException {
        MongoDatabase admin = client.getDatabase("admin");
        List<String> shards = new ArrayList<>();
        for (Document s : admin.runCommand(new Document("listShards", 1)).getList("shards", Document.class)) {
            shards.add(s.getString("_id"));
        }
        shards.sort(null);
        admin.runCommand(new Document("enableSharding", dbName).append("primaryShard", shards.get(0)));
        Map<String, String> keys = Map.of("ratings", "user_id", "to_read", "user_id", "user_stats", "_id");
        if (placement.equals("hashed")) {
            admin.runCommand(new Document("balancerStart", 1));
            for (Map.Entry<String, String> e : keys.entrySet()) {
                admin.runCommand(new Document("shardCollection", dbName + "." + e.getKey()).append("key", new Document(e.getValue(), "hashed")));
            }
            return new Document("placement", "hashed").append("shards", shards);
        }
        admin.runCommand(new Document("balancerStop", 1));
        List<Integer> points = splitPoints(prepared, shards.size());
        for (Map.Entry<String, String> e : keys.entrySet()) {
            String ns = dbName + "." + e.getKey();
            String field = e.getValue();
            admin.runCommand(new Document("shardCollection", ns).append("key", new Document(field, 1)));
            for (int p : points) {
                admin.runCommand(new Document("split", ns).append("middle", new Document(field, p)));
            }
            for (int i = 0; i < shards.size(); i++) {
                Object lower = i == 0 ? new MinKey() : points.get(i - 1);
                try {
                    admin.runCommand(new Document("moveChunk", ns).append("find", new Document(field, lower))
                            .append("to", shards.get(i)).append("_waitForDelete", true));
                } catch (MongoCommandException exc) { // already on that shard
                    if (!exc.getMessage().toLowerCase(Locale.ROOT).contains("already")) {
                        throw exc;
                    }
                }
            }
        }
        List<Document> ranges = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
            ranges.add(new Document("shard", shards.get(i)).append("from_user_id", i == 0 ? null : points.get(i - 1))
                    .append("to_user_id_excl", i < points.size() ? points.get(i) : null));
        }
        return new Document("placement", "range").append("shards", shards).append("ranges", ranges);
    }

    private static void step(Document timings, String name, Callable<Long> fn) throws Exception {
        long t = System.nanoTime();
        Long result = fn.call();
        double seconds = bookscope.Util.rnd((System.nanoTime() - t) / 1e9, 2);
        timings.append(name, seconds);
        System.out.printf(Locale.ROOT, "%-12s %10s  %7.2f s%n", name, result == null ? "" : result, seconds);
    }

    public static void run(Args args) throws Exception {
        String uri = args.get("--uri", Store.DEFAULT_URI);
        Path prepared = Path.of(args.get("--prepared", "data/prepared"));
        String placement = args.get("--placement", "range");
        String dbName = Store.DB_NAME;
        try (MongoClient client = MongoClients.create(uri)) {
            boolean sharded = "isdbgrid".equals(client.getDatabase("admin").runCommand(new Document("hello", 1)).getString("msg"));
            MongoDatabase db = client.getDatabase(dbName);
            Document timings = new Document();
            long t0 = System.nanoTime();
            db.drop();

            Document layout = new Document("placement", "single server");
            if (sharded) {
                layout = shardCollections(client, dbName, prepared, placement);
                System.out.println("Sharding: " + layout.toJson());
            }

            step(timings, "books", () -> insert(db.getCollection("books"), prepared.resolve("books.jsonl"), false));
            step(timings, "tags", () -> insert(db.getCollection("tags"), prepared.resolve("tags.jsonl"), false));
            step(timings, "ratings", () -> insert(db.getCollection("ratings"), prepared.resolve("ratings.jsonl"), true));
            step(timings, "to_read", () -> insert(db.getCollection("to_read"), prepared.resolve("to_read.jsonl"), true));
            step(timings, "indexes", () -> {
                MongoCollection<Document> ratings = db.getCollection("ratings");
                ratings.createIndex(Indexes.ascending("book_id", "rating", "user_id"), new IndexOptions());
                ratings.createIndex(Indexes.ascending("user_id", "rating", "book_id"), new IndexOptions());
                ratings.createIndex(Indexes.ascending("user_id", "seq"), new IndexOptions());
                db.getCollection("to_read").createIndex(Indexes.ascending("book_id"));
                db.getCollection("to_read").createIndex(Indexes.ascending("user_id"));
                db.getCollection("books").createIndex(Indexes.ascending("tags.t"));
                db.getCollection("tags").createIndex(Indexes.ascending("name"));
                return null;
            });
            step(timings, "statistics", () -> {
                MongoCollection<Document> ratings = db.getCollection("ratings");
                ratings.aggregate(List.of(
                        new Document("$group", new Document("_id", "$book_id").append("n", new Document("$sum", 1))
                                .append("sum", new Document("$sum", "$rating"))
                                .append("n_pos", new Document("$sum", new Document("$cond", List.of(new Document("$gte", List.of("$rating", 4)), 1, 0))))),
                        new Document("$merge", new Document("into", "book_stats").append("on", "_id")))).allowDiskUse(true).toCollection();
                ratings.aggregate(List.of(
                        new Document("$group", new Document("_id", "$user_id").append("n", new Document("$sum", 1))
                                .append("sum", new Document("$sum", "$rating"))),
                        new Document("$merge", new Document("into", "user_stats").append("on", "_id")))).allowDiskUse(true).toCollection();
                Object last = ratings.find().sort(Sorts.descending("seq")).first().get("seq");
                db.getCollection("meta").replaceOne(new Document("_id", "seq"), new Document("_id", "seq").append("value", last),
                        new ReplaceOptions().upsert(true));
                return null;
            });

            timings.append("total", bookscope.Util.rnd((System.nanoTime() - t0) / 1e9, 2));
            Document size = db.runCommand(new Document("dbStats", 1));
            Document storage = new Document();
            for (String key : List.of("dataSize", "storageSize", "indexSize", "totalSize")) {
                storage.append(key, size.get(key));
            }
            if (sharded && size.get("raw") instanceof Document raw) {
                Document perShard = new Document();
                raw.forEach((shard, value) -> {
                    Document v = (Document) value;
                    perShard.append(shard, new Document("dataSize", v.get("dataSize")).append("storageSize", v.get("storageSize"))
                            .append("indexSize", v.get("indexSize")));
                });
                storage.append("per_shard", perShard);
            }
            Document report = Document.parse(Files.readString(prepared.resolve("report.json"), StandardCharsets.UTF_8));
            db.getCollection("meta").replaceOne(new Document("_id", "load"),
                    new Document("_id", "load").append("selection_rule", report.get("selection_rule")).append("counts", report.get("counts"))
                            .append("layout", layout).append("timings_s", timings).append("storage_bytes", storage),
                    new ReplaceOptions().upsert(true));
            System.out.println("Loaded in " + timings.get("total") + " s; storage: " + storage.toJson());
        }
    }
}
