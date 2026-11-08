package bookscope.tools;

import static bookscope.Util.LIKE;
import static bookscope.Util.toInt;

import bookscope.Store;
import bookscope.Util;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Part I, alternative representation: one document per reader instead of one document per rating.
 *
 * <pre>
 *   current      ratings  {_id: "u:b", user_id, book_id, rating, seq}        one document per rating
 *   alternative  readers  {_id: user_id, r: [{b: book_id, r: rating, s: seq}, ...]}  sorted by seq
 * </pre>
 *
 * <p>Two representative requests are measured on both representations:
 * <ul>
 *   <li>history: all ratings of one reader in time order (used by compare and recommend);</li>
 *   <li>likers: the readers who rated one book 4 or more (used by explore and trace).</li>
 * </ul>
 * Each request is run once to warm the cache, then timed {@code --repeat} times; the median is kept.
 * The plan of each request (keys and documents examined) is read with explain.
 *
 * <pre>
 *   java -jar target/bookscope.jar representation --uri mongodb://localhost:27100 --output results
 * </pre>
 */
public final class ExperimentRepresentation {
    private static final String ALT = "readers_alt";

    private ExperimentRepresentation() {
    }

    /** Builds readers_alt from ratings with one aggregation: sort by reader and time, then group. */
    private static void build(MongoDatabase db) {
        long start = System.nanoTime();
        db.getCollection(ALT).drop();
        List<Document> pipeline = List.of(
                new Document("$sort", new Document("user_id", 1).append("seq", 1)),
                new Document("$group", new Document("_id", "$user_id")
                        .append("r", new Document("$push", new Document("b", "$book_id").append("r", "$rating").append("s", "$seq")))),
                new Document("$out", ALT));
        db.getCollection("ratings").aggregate(pipeline).allowDiskUse(true).toCollection();
        db.getCollection(ALT).createIndex(Indexes.ascending("r.b", "r.r"));
        System.out.printf(Locale.ROOT, "built %s: %d readers in %.1f s%n", ALT,
                db.getCollection(ALT).countDocuments(), (System.nanoTime() - start) / 1e9);
    }

    private static Document storage(MongoDatabase db, String name) {
        Document s = db.getCollection(name).aggregate(List.of(
                new Document("$collStats", new Document("storageStats", new Document("scale", 1024 * 1024))))).first()
                .get("storageStats", Document.class);
        return new Document("collection", name).append("documents", s.get("count"))
                .append("avg_document_bytes", s.get("avgObjSize")).append("data_mb", s.get("size"))
                .append("storage_mb", s.get("storageSize")).append("index_mb", s.get("totalIndexSize"));
    }

    private static double medianMs(Supplier<Object> run, int repeat) {
        run.get();
        double[] t = new double[repeat];
        for (int i = 0; i < repeat; i++) {
            long start = System.nanoTime();
            run.get();
            t[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(t);
        return repeat % 2 == 1 ? t[repeat / 2] : (t[repeat / 2 - 1] + t[repeat / 2]) / 2;
    }

    /** keys and documents examined by a find, from explain("executionStats"). */
    private static int[] plan(MongoDatabase db, String coll, Bson filter, Document projection, Document sort) {
        Document find = new Document("find", coll).append("filter", filter).append("projection", projection);
        if (sort != null) {
            find.append("sort", sort);
        }
        Document stats = db.runCommand(new Document("explain", find).append("verbosity", "executionStats"))
                .get("executionStats", Document.class);
        return new int[] {toInt(stats.get("totalKeysExamined")), toInt(stats.get("totalDocsExamined"))};
    }

    // --- the two requests, on each representation -------------------------------------------

    private static List<Integer> historyCurrent(MongoCollection<Document> ratings, int user) {
        List<Integer> out = new ArrayList<>();
        for (Document d : ratings.find(new Document("user_id", user)).projection(new Document("_id", 0).append("book_id", 1)
                .append("rating", 1).append("seq", 1)).sort(new Document("seq", 1))) {
            out.add(toInt(d.get("book_id")));
        }
        return out;
    }

    private static List<Integer> historyAlternative(MongoCollection<Document> readers, int user) {
        List<Integer> out = new ArrayList<>();
        Document d = readers.find(new Document("_id", user)).first();
        if (d != null) {
            for (Document r : d.getList("r", Document.class)) {
                out.add(toInt(r.get("b")));
            }
        }
        return out;
    }

    private static Document likersFilterCurrent(int book) {
        return new Document("book_id", book).append("rating", new Document("$gte", LIKE));
    }

    private static Document likersFilterAlternative(int book) {
        return new Document("r", new Document("$elemMatch", new Document("b", book).append("r", new Document("$gte", LIKE))));
    }

    private static Set<Integer> likers(MongoCollection<Document> coll, Document filter, String field) {
        Set<Integer> out = new HashSet<>();
        for (Document d : coll.find(filter).projection(new Document("_id", 0).append(field, 1))) {
            out.add(toInt(d.get(field)));
        }
        return out;
    }

    private static Set<Integer> likersAlternative(MongoCollection<Document> readers, int book) {
        Set<Integer> out = new HashSet<>();
        for (Document d : readers.find(likersFilterAlternative(book)).projection(new Document("_id", 1))) {
            out.add(toInt(d.get("_id")));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private record Row(String request, int item, int results, double currentMs, int currentKeys, int currentDocs,
                       double alternativeMs, int alternativeKeys, int alternativeDocs) {
    }

    private static Document summary(List<Row> rows) {
        Document out = new Document("items", rows.size());
        out.append("results_median", Util.median(rows.stream().map(r -> (double) r.results).toList()));
        out.append("current_ms_median", Util.rnd(Util.median(rows.stream().map(r -> r.currentMs).toList()), 3));
        out.append("alternative_ms_median", Util.rnd(Util.median(rows.stream().map(r -> r.alternativeMs).toList()), 3));
        out.append("current_docs_examined_median", Util.median(rows.stream().map(r -> (double) r.currentDocs).toList()));
        out.append("alternative_docs_examined_median", Util.median(rows.stream().map(r -> (double) r.alternativeDocs).toList()));
        out.append("current_keys_examined_median", Util.median(rows.stream().map(r -> (double) r.currentKeys).toList()));
        out.append("alternative_keys_examined_median", Util.median(rows.stream().map(r -> (double) r.alternativeKeys).toList()));
        out.append("alternative_faster_items", rows.stream().filter(r -> r.alternativeMs < r.currentMs).count());
        return out;
    }

    public static void run(Args args) throws Exception {
        String uri = args.get("--uri", Store.DEFAULT_URI);
        Path output = Path.of(args.get("--output", "results"));
        int samples = args.getInt("--samples", 100);
        int repeat = args.getInt("--repeat", 5);
        Files.createDirectories(output);
        try (Store store = new Store(uri)) {
            MongoDatabase db = store.db;
            if (!args.has("--no-build") || db.getCollection(ALT).estimatedDocumentCount() == 0) {
                build(db);
            }
            MongoCollection<Document> ratings = db.getCollection("ratings");
            MongoCollection<Document> readers = db.getCollection(ALT);

            // Fixed samples: random readers (seed 42) and books spread over the popularity range.
            List<Integer> users = new ArrayList<>();
            for (Document d : db.getCollection("user_stats").find().projection(new Document("_id", 1)).sort(new Document("_id", 1))) {
                users.add(toInt(d.get("_id")));
            }
            java.util.Collections.shuffle(users, new Random(42));
            users = new ArrayList<>(users.subList(0, Math.min(samples, users.size())));
            users.sort(null);
            List<Integer> byPopularity = new ArrayList<>();
            for (Document d : db.getCollection("book_stats").find(new Document("n", new Document("$gt", 0)))
                    .projection(new Document("_id", 1)).sort(new Document("n", 1).append("_id", 1))) {
                byPopularity.add(toInt(d.get("_id")));
            }
            List<Integer> books = new ArrayList<>();
            for (int i = 0; i < samples; i++) {
                books.add(byPopularity.get((int) ((long) i * (byPopularity.size() - 1) / Math.max(1, samples - 1))));
            }

            List<Row> history = new ArrayList<>();
            int mismatches = 0;
            for (int u : users) {
                List<Integer> a = historyCurrent(ratings, u);
                List<Integer> b = historyAlternative(readers, u);
                mismatches += a.equals(b) ? 0 : 1;
                int[] pc = plan(db, "ratings", new Document("user_id", u), new Document("_id", 0).append("book_id", 1).append("rating", 1).append("seq", 1), new Document("seq", 1));
                int[] pa = plan(db, ALT, new Document("_id", u), new Document(), null);
                history.add(new Row("history", u, a.size(),
                        medianMs(() -> historyCurrent(ratings, u), repeat), pc[0], pc[1],
                        medianMs(() -> historyAlternative(readers, u), repeat), pa[0], pa[1]));
            }
            List<Row> likers = new ArrayList<>();
            for (int b : books) {
                Set<Integer> a = likers(ratings, likersFilterCurrent(b), "user_id");
                Set<Integer> c = likersAlternative(readers, b);
                mismatches += a.equals(c) ? 0 : 1;
                int[] pc = plan(db, "ratings", likersFilterCurrent(b), new Document("_id", 0).append("user_id", 1), null);
                int[] pa = plan(db, ALT, likersFilterAlternative(b), new Document("_id", 1), null);
                likers.add(new Row("likers", b, a.size(),
                        medianMs(() -> likers(ratings, likersFilterCurrent(b), "user_id"), repeat), pc[0], pc[1],
                        medianMs(() -> likersAlternative(readers, b), repeat), pa[0], pa[1]));
            }

            try (BufferedWriter w = Files.newBufferedWriter(output.resolve("representation_raw.csv"), StandardCharsets.UTF_8)) {
                w.write("request,item,results,current_ms,current_keys_examined,current_docs_examined,alternative_ms,alternative_keys_examined,alternative_docs_examined\n");
                for (Row r : concat(history, likers)) {
                    w.write(String.format(Locale.ROOT, "%s,%d,%d,%.3f,%d,%d,%.3f,%d,%d%n", r.request, r.item, r.results,
                            r.currentMs, r.currentKeys, r.currentDocs, r.alternativeMs, r.alternativeKeys, r.alternativeDocs));
                }
            }
            Map<String, Object> requests = new LinkedHashMap<>();
            requests.put("history", summary(history));
            requests.put("likers", summary(likers));
            Document out = new Document("uri", uri).append("samples", samples).append("repeat", repeat)
                    .append("results_identical", mismatches == 0).append("mismatches", mismatches)
                    .append("storage", List.of(storage(db, "ratings"), storage(db, ALT)))
                    .append("requests", new Document(requests));
            Files.writeString(output.resolve("representation_summary.json"),
                    out.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()), StandardCharsets.UTF_8);

            System.out.println();
            System.out.println("same results on both representations: " + (mismatches == 0 ? "yes" : "NO (" + mismatches + " differences)"));
            System.out.println();
            System.out.printf("%-10s %-28s %-28s%n", "storage", "current (ratings)", "alternative (readers_alt)");
            Document s0 = storage(db, "ratings");
            Document s1 = storage(db, ALT);
            for (String key : List.of("documents", "avg_document_bytes", "data_mb", "storage_mb", "index_mb")) {
                System.out.printf("%-20s %-18s %-18s%n", key, s0.get(key), s1.get(key));
            }
            System.out.println();
            System.out.printf("%-8s %-22s %12s %12s %16s %16s%n", "request", "median over " + samples + " items", "current ms", "altern. ms", "docs (cur/alt)", "keys (cur/alt)");
            for (Map.Entry<String, Object> e : requests.entrySet()) {
                Document s = (Document) e.getValue();
                System.out.printf(Locale.ROOT, "%-8s %-22s %12.3f %12.3f %16s %16s%n", e.getKey(), "results=" + s.get("results_median"),
                        s.getDouble("current_ms_median"), s.getDouble("alternative_ms_median"),
                        fmt(s.get("current_docs_examined_median")) + " / " + fmt(s.get("alternative_docs_examined_median")),
                        fmt(s.get("current_keys_examined_median")) + " / " + fmt(s.get("alternative_keys_examined_median")));
            }
            System.out.println();
            System.out.println("written " + output.resolve("representation_raw.csv") + " and " + output.resolve("representation_summary.json"));
        }
    }

    private static String fmt(Object v) {
        double d = ((Number) v).doubleValue();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    private static List<Row> concat(List<Row> a, List<Row> b) {
        List<Row> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
