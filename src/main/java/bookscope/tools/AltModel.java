package bookscope.tools;

import bookscope.BookScopeException;
import bookscope.Store;
import bookscope.Util;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

public final class AltModel {
    private static final String ALT = "reader_history";

    private AltModel() {
    }

    private static Document size(Store store, String collection) {
        Document s = store.db.getCollection(collection).aggregate(List.of(
                new Document("$collStats", new Document("storageStats", new Document("scale", 1024 * 1024))))).first();
        Document st = s == null ? new Document() : s.get("storageStats", Document.class);
        return new Document("documents", st.get("count")).append("data_mb", st.get("size"))
                .append("storage_mb", st.get("storageSize")).append("indexes_mb", st.get("totalIndexSize"));
    }

    private static Document build(Store store) {
        store.db.getCollection(ALT).drop();
        long t = System.nanoTime();
        store.db.getCollection("ratings").aggregate(List.of(
                new Document("$group", new Document("_id", "$user_id").append("ratings",
                        new Document("$push", new Document("b", "$book_id").append("r", "$rating").append("s", "$seq")))),
                new Document("$set", new Document("ratings",
                        new Document("$sortArray", new Document("input", "$ratings").append("sortBy", new Document("s", 1))))),
                new Document("$out", ALT))).allowDiskUse(true).toCollection();
        double groupSeconds = (System.nanoTime() - t) / 1e9;
        t = System.nanoTime();
        store.db.getCollection(ALT).createIndex(Indexes.ascending("ratings.b", "ratings.r"));
        double indexSeconds = (System.nanoTime() - t) / 1e9;
        return new Document("build_seconds", Util.rnd(groupSeconds, 2)).append("index_seconds", Util.rnd(indexSeconds, 2));
    }

    private static List<Integer> ids(Store store, String collection, int minN) {
        List<Integer> out = new ArrayList<>();
        for (Document d : store.db.getCollection(collection).find(new Document("n", new Document("$gte", minN)))
                .projection(new Document("_id", 1)).sort(new Document("_id", 1))) {
            out.add(Util.toInt(d.get("_id")));
        }
        return out;
    }

    private static List<String> historyPerRating(Store store, int user) {
        List<String> out = new ArrayList<>();
        for (Store.Rating r : store.history(user, null)) {
            out.add(r.bookId() + ":" + r.rating() + ":" + r.seq());
        }
        return out;
    }

    private static List<String> historyPerReader(MongoCollection<Document> alt, int user) {
        List<String> out = new ArrayList<>();
        Document d = alt.find(new Document("_id", user)).first();
        if (d != null) {
            for (Document r : d.getList("ratings", Document.class)) {
                out.add(Util.toInt(r.get("b")) + ":" + Util.toInt(r.get("r")) + ":" + Util.toInt(r.get("s")));
            }
        }
        return out;
    }

    private static List<Integer> likersPerRating(Store store, int book) {
        List<Integer> out = new ArrayList<>(store.likers(book));
        out.sort(null);
        return out;
    }

    private static List<Integer> likersPerReader(MongoCollection<Document> alt, int book) {
        List<Integer> out = new ArrayList<>();
        Document filter = new Document("ratings", new Document("$elemMatch",
                new Document("b", book).append("r", new Document("$gte", Util.LIKE))));
        for (Document d : alt.find(filter).projection(new Document("_id", 1))) {
            out.add(Util.toInt(d.get("_id")));
        }
        out.sort(null);
        return out;
    }

    private static double quantile(List<Double> values, double q) {
        List<Double> sorted = values.stream().sorted().toList();
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    private static Document describe(List<Double> values) {
        return new Document("n", values.size()).append("median_ms", Util.rnd(quantile(values, 0.5), 3))
                .append("p25_ms", Util.rnd(quantile(values, 0.25), 3)).append("p75_ms", Util.rnd(quantile(values, 0.75), 3));
    }

    public static void run(Args args) throws Exception {
        String uri = args.get("--uri", Store.DEFAULT_URI);
        int count = args.getInt("--requests", 200);
        int repeats = args.getInt("--repeats", 5);
        long seed = args.getInt("--seed", 42);
        Path output = Path.of(args.get("--output", "results"));
        Files.createDirectories(output);
        Document summary = new Document("uri", uri).append("database", Store.DB_NAME).append("requests", count)
                .append("repeats", repeats).append("seed", seed);
        try (Store store = new Store(uri);
             BufferedWriter raw = Files.newBufferedWriter(output.resolve("alt_model_raw.csv"), StandardCharsets.UTF_8)) {
            Document hello = store.client.getDatabase("admin").runCommand(new Document("hello", 1));
            if ("isdbgrid".equals(hello.getString("msg"))) {
                throw new BookScopeException("run alt-model on the local server, not on mongos");
            }
            summary.append("build", build(store));
            summary.append("size_per_rating", size(store, "ratings")).append("size_per_reader", size(store, ALT));
            MongoCollection<Document> alt = store.db.getCollection(ALT);
            Random rnd = new Random(seed);
            List<Integer> readers = ids(store, "user_stats", 1);
            List<Integer> books = ids(store, "book_stats", 1);
            List<Integer> userSample = new ArrayList<>();
            List<Integer> bookSample = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                userSample.add(readers.get(rnd.nextInt(readers.size())));
                bookSample.add(books.get(rnd.nextInt(books.size())));
            }
            int mismatches = 0;
            for (int i = 0; i < Math.min(20, count); i++) {
                historyPerRating(store, userSample.get(i));
                historyPerReader(alt, userSample.get(i));
                likersPerRating(store, bookSample.get(i));
                likersPerReader(alt, bookSample.get(i));
            }
            List<Double> hRating = new ArrayList<>();
            List<Double> hReader = new ArrayList<>();
            List<Double> lRating = new ArrayList<>();
            List<Double> lReader = new ArrayList<>();
            raw.write("repeat,request,input,model,ms,result_size\n");
            for (int rep = 1; rep <= repeats; rep++) {
                for (int i = 0; i < count; i++) {
                    int u = userSample.get(i);
                    int b = bookSample.get(i);
                    long t = System.nanoTime();
                    List<String> a1 = historyPerRating(store, u);
                    double m1 = (System.nanoTime() - t) / 1e6;
                    t = System.nanoTime();
                    List<String> a2 = historyPerReader(alt, u);
                    double m2 = (System.nanoTime() - t) / 1e6;
                    t = System.nanoTime();
                    List<Integer> c1 = likersPerRating(store, b);
                    double m3 = (System.nanoTime() - t) / 1e6;
                    t = System.nanoTime();
                    List<Integer> c2 = likersPerReader(alt, b);
                    double m4 = (System.nanoTime() - t) / 1e6;
                    if (!a1.equals(a2) || !c1.equals(c2)) {
                        mismatches++;
                    }
                    hRating.add(m1);
                    hReader.add(m2);
                    lRating.add(m3);
                    lReader.add(m4);
                    raw.write(String.format(Locale.ROOT, "%d,history,%d,per_rating,%.3f,%d%n", rep, u, m1, a1.size()));
                    raw.write(String.format(Locale.ROOT, "%d,history,%d,per_reader,%.3f,%d%n", rep, u, m2, a2.size()));
                    raw.write(String.format(Locale.ROOT, "%d,likers,%d,per_rating,%.3f,%d%n", rep, b, m3, c1.size()));
                    raw.write(String.format(Locale.ROOT, "%d,likers,%d,per_reader,%.3f,%d%n", rep, b, m4, c2.size()));
                }
            }
            summary.append("identical_results", mismatches == 0).append("mismatches", mismatches)
                    .append("reader_history", new Document("per_rating", describe(hRating)).append("per_reader", describe(hReader)))
                    .append("book_likers", new Document("per_rating", describe(lRating)).append("per_reader", describe(lReader)));
            System.out.printf(Locale.ROOT, "reader history  per_rating %.3f ms  per_reader %.3f ms%n",
                    quantile(hRating, 0.5), quantile(hReader, 0.5));
            System.out.printf(Locale.ROOT, "book likers     per_rating %.3f ms  per_reader %.3f ms%n",
                    quantile(lRating, 0.5), quantile(lReader, 0.5));
            System.out.println("identical results: " + (mismatches == 0) + " (mismatches " + mismatches + ")");
            System.out.println("sizes: " + summary.get("size_per_rating") + " vs " + summary.get("size_per_reader"));
        }
        Path json = output.resolve("alt_model_summary.json");
        Files.writeString(json, summary.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()),
                StandardCharsets.UTF_8);
        System.out.println("written: " + json);
    }
}