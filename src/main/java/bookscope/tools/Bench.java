package bookscope.tools;

import bookscope.BookScopeException;
import bookscope.Compare;
import bookscope.Explore;
import bookscope.Interests;
import bookscope.Recommend;
import bookscope.Store;
import bookscope.Trace;
import bookscope.Util;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

public final class Bench {
    private static final List<String> TAGS = List.of("fantasy", "romance", "vampires", "dystopia", "young-adult", "mystery",
            "science-fiction", "historical-fiction", "horror", "classics", "thriller", "paranormal");
    private static final List<String> COUNTERS = List.of("opcounters.query", "opcounters.getmore", "opcounters.command",
            "metrics.document.returned", "metrics.queryExecutor.scanned", "metrics.queryExecutor.scannedObjects");

    private record Request(String service, String kind, int a, int b, String tagA, String tagB) {
        String input() {
            return switch (service) {
                case "F2", "F4" -> a + " " + b;
                case "F5" -> tagA + " " + tagB;
                default -> String.valueOf(a);
            };
        }
    }

    private Bench() {
    }

    private static List<Integer> ids(Store store, String collection, int minN) {
        List<Integer> out = new ArrayList<>();
        for (Document d : store.db.getCollection(collection).find(new Document("n", new Document("$gte", minN)))
                .projection(new Document("_id", 1)).sort(new Document("_id", 1))) {
            out.add(Util.toInt(d.get("_id")));
        }
        if (out.isEmpty()) {
            throw new BookScopeException("no data in " + collection + "; run the load command first");
        }
        return out;
    }

    private static List<Request> requests(List<Integer> readers, List<Integer> books, double share, int count, long seed) {
        Random rnd = new Random(seed * 1000 + Math.round(share * 100));
        List<Request> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int r1 = readers.get(rnd.nextInt(readers.size()));
            int r2 = readers.get(rnd.nextInt(readers.size()));
            while (r2 == r1) {
                r2 = readers.get(rnd.nextInt(readers.size()));
            }
            int b1 = books.get(rnd.nextInt(books.size()));
            int b2 = books.get(rnd.nextInt(books.size()));
            while (b2 == b1) {
                b2 = books.get(rnd.nextInt(books.size()));
            }
            String t1 = TAGS.get(rnd.nextInt(TAGS.size()));
            String t2 = TAGS.get(rnd.nextInt(TAGS.size()));
            while (t2.equals(t1)) {
                t2 = TAGS.get(rnd.nextInt(TAGS.size()));
            }
            int pick = rnd.nextInt(6);
            if (rnd.nextDouble() < share) {
                out.add(pick < 3 ? new Request("F2", "personal", r1, r2, null, null) : new Request("F3", "personal", r1, 0, null, null));
            } else if (pick < 2) {
                out.add(new Request("F1", "explore", b1, 0, null, null));
            } else if (pick < 4) {
                out.add(new Request("F4", "explore", b1, b2, null, null));
            } else {
                out.add(new Request("F5", "explore", 0, 0, t1, t2));
            }
        }
        return out;
    }

    private static String run(Store store, Request r) {
        try {
            Document d = switch (r.service()) {
                case "F1" -> Explore.explore(store, null, r.a(), 10, 5);
                case "F2" -> Compare.compare(store, r.a(), r.b(), null, null, 10);
                case "F3" -> Recommend.recommend(store, r.a(), 10, null);
                case "F4" -> Trace.trace(store, r.a(), r.b(), 5);
                default -> Interests.interests(store, r.tagA(), r.tagB(), 5, Interests.MIN_TAG_COUNT);
            };
            return d.getString("status");
        } catch (MongoException exc) {
            return "unavailable";
        } catch (RuntimeException exc) {
            return "error";
        }
    }

    private static List<String> instances(Store store) {
        List<String> out = new ArrayList<>();
        Document hello = store.client.getDatabase("admin").runCommand(new Document("hello", 1));
        if ("isdbgrid".equals(hello.getString("msg"))) {
            Document shards = store.client.getDatabase("admin").runCommand(new Document("listShards", 1));
            for (Document s : shards.getList("shards", Document.class)) {
                String host = s.getString("host");
                out.add(s.getString("_id") + "=" + host.substring(host.indexOf('/') + 1).split(",")[0]);
            }
        } else {
            out.add("local=" + store.uri.replace("mongodb://", "").split("/")[0]);
        }
        return out;
    }

    private static long counter(Document status, String path) {
        Object value = status;
        for (String key : path.split("\\.")) {
            if (!(value instanceof Document doc) || !doc.containsKey(key)) {
                return 0;
            }
            value = doc.get(key);
        }
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static Map<String, Map<String, Long>> snapshot(List<String> instances) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (String instance : instances) {
            String[] parts = instance.split("=");
            Map<String, Long> values = new LinkedHashMap<>();
            try (MongoClient c = MongoClients.create("mongodb://" + parts[1] + "/?directConnection=true&serverSelectionTimeoutMS=2000")) {
                Document status = c.getDatabase("admin").runCommand(new Document("serverStatus", 1));
                for (String key : COUNTERS) {
                    values.put(key, counter(status, key));
                }
            } catch (MongoException exc) {
                values.put("unreachable", 1L);
            }
            out.put(parts[0], values);
        }
        return out;
    }

    private static Document delta(Map<String, Map<String, Long>> before, Map<String, Map<String, Long>> after) {
        Document out = new Document();
        for (String instance : after.keySet()) {
            Document d = new Document();
            for (Map.Entry<String, Long> e : after.get(instance).entrySet()) {
                d.append(e.getKey(), e.getValue() - before.getOrDefault(instance, Map.of()).getOrDefault(e.getKey(), 0L));
            }
            out.append(instance, d);
        }
        return out;
    }

    private static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    private static Document describe(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / Math.max(1, values.size() - 1);
        return new Document("n", values.size()).append("median", Util.rnd(quantile(values, 0.5), 2))
                .append("p25", Util.rnd(quantile(values, 0.25), 2)).append("p75", Util.rnd(quantile(values, 0.75), 2))
                .append("mean", Util.rnd(mean, 2)).append("std", Util.rnd(Math.sqrt(var), 2));
    }

    private static Document environment(Store store) {
        Document build = store.client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
        Document stats = store.db.runCommand(new Document("dbStats", 1).append("scale", 1024 * 1024));
        Document load = store.db.getCollection("meta").find(new Document("_id", "load")).first();
        Runtime rt = Runtime.getRuntime();
        return new Document("mongodb", build.getString("version")).append("java", System.getProperty("java.version"))
                .append("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .append("cpus", rt.availableProcessors()).append("jvm_max_mb", rt.maxMemory() / (1024 * 1024))
                .append("storage_mb", new Document("data", stats.get("dataSize")).append("storage", stats.get("storageSize"))
                        .append("indexes", stats.get("indexSize")).append("total", stats.get("totalSize")))
                .append("load", load);
    }

    public static void run(Args args) throws Exception {
        String uri = args.get("--uri", Store.DEFAULT_URI);
        String label = args.get("--label", "run");
        int count = args.getInt("--requests", 40);
        int repeats = args.getInt("--repeats", 5);
        int warmup = args.getInt("--warmup", 10);
        long seed = args.getInt("--seed", 42);
        Path output = Path.of(args.get("--output", "results"));
        List<Double> shares = new ArrayList<>();
        for (String s : args.get("--shares", "1,0.75,0.5,0.25,0").split(",")) {
            shares.add(Double.parseDouble(s.strip()));
        }
        Files.createDirectories(output);
        Document summary = new Document("label", label).append("uri", uri).append("requests_per_batch", count)
                .append("repeats", repeats).append("warmup_requests", warmup).append("seed", seed)
                .append("cache", "warm: catalogue loaded and " + warmup + " requests run before timing, servers not restarted between runs");
        try (Store store = new Store(uri);
             BufferedWriter raw = Files.newBufferedWriter(output.resolve("bench_" + label + "_raw.csv"), StandardCharsets.UTF_8)) {
            store.catalog();
            summary.append("environment", environment(store));
            List<String> instances = instances(store);
            summary.append("instances", instances);
            List<Integer> readers = ids(store, "user_stats", 1);
            List<Integer> books = ids(store, "book_stats", 20);
            raw.write("label,personal_share,repeat,index,service,kind,input,status,ms\n");
            List<Document> runs = new ArrayList<>();
            for (double share : shares) {
                List<Request> list = requests(readers, books, share, count, seed);
                for (Request r : list.subList(0, Math.min(warmup, list.size()))) {
                    run(store, r);
                }
                Map<String, Map<String, Long>> before = snapshot(instances);
                List<Double> batches = new ArrayList<>();
                Map<String, List<Double>> perService = new TreeMap<>();
                Map<String, Integer> statuses = new TreeMap<>();
                for (int rep = 1; rep <= repeats; rep++) {
                    long batchStart = System.nanoTime();
                    for (int i = 0; i < list.size(); i++) {
                        Request r = list.get(i);
                        long t = System.nanoTime();
                        String status = run(store, r);
                        double ms = (System.nanoTime() - t) / 1e6;
                        perService.computeIfAbsent(r.service(), x -> new ArrayList<>()).add(ms);
                        statuses.merge(status, 1, Integer::sum);
                        raw.write(String.format(Locale.ROOT, "%s,%.2f,%d,%d,%s,%s,%s,%s,%.3f%n",
                                label, share, rep, i, r.service(), r.kind(), r.input(), status, ms));
                    }
                    batches.add((System.nanoTime() - batchStart) / 1e9);
                }
                Document services = new Document();
                perService.forEach((k, v) -> services.append(k, describe(v)));
                Document batch = describe(batches);
                Document result = new Document("personal_share", share).append("batch_seconds", batch)
                        .append("requests_per_second", Util.rnd(count / quantile(batches, 0.5), 2))
                        .append("services_ms", services).append("statuses", new Document(new LinkedHashMap<String, Object>(statuses)))
                        .append("work_per_instance", delta(before, snapshot(instances)));
                runs.add(result);
                System.out.printf(Locale.ROOT, "%s share=%.2f batch median=%.2fs [p25 %.2f, p75 %.2f] %s%n",
                        label, share, batch.getDouble("median"), batch.getDouble("p25"), batch.getDouble("p75"), statuses);
            }
            summary.append("runs", runs);
        }
        Path json = output.resolve("bench_" + label + "_summary.json");
        Files.writeString(json, summary.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()),
                StandardCharsets.UTF_8);
        System.out.println("written: " + json);
    }
}