package bookscope.tools;

import static bookscope.Util.LIKE;
import static bookscope.Util.toInt;

import bookscope.Catalog;
import bookscope.Recommend;
import bookscope.Store;
import bookscope.Util;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * E3: does F3 recover the ratings withheld in eval_set.jsonl, compared with a popularity baseline?
 *
 * <p>A reader is eligible when at least one withheld rating is >= 4: these books are the relevant
 * ones. Each eligible reader is evaluated with the complete stored history and with the history
 * truncated to its k earliest ratings. Books the reader rated in the rest of the stored history are
 * removed from every list, so that the truncated runs are not penalised for proposing them.
 *
 * <p>The "readers only" run sets the tag share to 0 on truncated histories. Tag counts are aggregates
 * over all of Goodreads and may include the withheld period, so it measures how much the score
 * depends on this possible leak.
 *
 * <pre>
 *   java -jar target/bookscope.jar e3 --uri mongodb://localhost:27100 --output results
 * </pre>
 */
public final class ExperimentE3 {
    private static final List<String> RUNS = List.of("f3_complete", "baseline_complete", "f3_truncated", "baseline_truncated", "f3_truncated_readers_only");
    private static final List<String> METRICS = List.of("precision", "recall", "hit", "share_top100", "median_popularity", "seconds");

    private record Result(String run, int user, int relevant, int listed, int hits, double[] metrics) {
    }

    private ExperimentE3() {
    }

    private static Map<Integer, Set<Integer>> loadRelevant(Path path) throws IOException {
        Map<Integer, Set<Integer>> relevant = new TreeMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Document r = Document.parse(line);
            if (toInt(r.get("rating")) >= LIKE) {
                relevant.computeIfAbsent(toInt(r.get("user_id")), x -> new HashSet<>()).add(toInt(r.get("book_id")));
            }
        }
        return relevant;
    }

    private static Result score(List<Integer> listed, Set<Integer> relevant, Catalog cat, String run, int user, double seconds) {
        long hits = listed.stream().filter(relevant::contains).count();
        long popular = listed.stream().filter(cat.top100()::contains).count();
        double[] metrics = {
            listed.isEmpty() ? 0.0 : (double) hits / listed.size(),
            (double) hits / relevant.size(),
            hits > 0 ? 1 : 0,
            listed.isEmpty() ? 0.0 : (double) popular / listed.size(),
            listed.isEmpty() ? 0 : Util.median(listed.stream().map(cat::n).toList()),
            seconds};
        return new Result(run, user, relevant.size(), listed.size(), (int) hits, metrics);
    }

    private static Result f3(Store store, int user, Set<Integer> known, Set<Integer> relevant, int k, Integer truncate, double tagShare, String run) {
        long start = System.nanoTime();
        Document result = Recommend.recommend(store, user, k + known.size(), truncate, Recommend.POPULARITY_PENALTY, tagShare);
        List<Integer> listed = new ArrayList<>();
        for (Document r : result.getList("recommendations", Document.class)) {
            int book = r.getInteger("book_id");
            if (!known.contains(book) && listed.size() < k) {
                listed.add(book);
            }
        }
        return score(listed, relevant, store.catalog(), run, user, (System.nanoTime() - start) / 1e9);
    }

    private static Document summarise(List<Result> rows) {
        Document summary = new Document();
        for (String run : RUNS) {
            List<Result> selected = rows.stream().filter(r -> r.run().equals(run)).toList();
            Document out = new Document("readers", selected.size());
            for (int m = 0; m < METRICS.size(); m++) {
                double sum = 0;
                double squares = 0;
                for (Result r : selected) {
                    sum += r.metrics()[m];
                }
                double mean = sum / selected.size();
                for (Result r : selected) {
                    squares += (r.metrics()[m] - mean) * (r.metrics()[m] - mean);
                }
                double error = selected.size() > 1 ? Math.sqrt(squares / (selected.size() - 1)) / Math.sqrt(selected.size()) : 0.0;
                out.append(METRICS.get(m), new Document("mean", Util.rnd(mean, 5)).append("std_error", Util.rnd(error, 5)));
            }
            summary.append(run, out);
        }
        return summary;
    }

    public static void run(Args args) throws IOException {
        int k = args.getInt("--k", 10);
        int truncate = args.getInt("--truncate", 5);
        Path output = Path.of(args.get("--output", "results"));
        Map<Integer, Set<Integer>> relevant = loadRelevant(Path.of(args.get("--eval-set", "data/prepared/eval_set.jsonl")));
        List<Integer> readers = new ArrayList<>(relevant.keySet());
        if (args.has("--limit")) {
            readers = readers.subList(0, Math.min(args.getInt("--limit", 0), readers.size()));
        }
        String uri = args.get("--uri", Store.DEFAULT_URI);
        List<Result> rows = new ArrayList<>();
        try (Store store = new Store(uri)) {
            Catalog cat = store.catalog();
            int done = 0;
            for (int user : readers) {
                Set<Integer> rel = relevant.get(user);
                Set<Integer> known = new HashSet<>();
                store.history(user, null).forEach(r -> known.add(r.bookId()));
                List<Integer> popular = Recommend.popularityBaseline(cat, known, k);
                rows.add(score(popular, rel, cat, "baseline_complete", user, 0.0));
                rows.add(score(popular, rel, cat, "baseline_truncated", user, 0.0));
                rows.add(f3(store, user, known, rel, k, null, Recommend.TAG_SHARE, "f3_complete"));
                rows.add(f3(store, user, known, rel, k, truncate, Recommend.TAG_SHARE, "f3_truncated"));
                rows.add(f3(store, user, known, rel, k, truncate, 0.0, "f3_truncated_readers_only"));
                if (++done % 100 == 0) {
                    System.out.println(done + "/" + readers.size() + " readers");
                }
            }
        }
        Files.createDirectories(output);
        try (BufferedWriter out = Files.newBufferedWriter(output.resolve("e3_raw.csv"), StandardCharsets.UTF_8)) {
            out.write("run,user_id,relevant,listed,hits," + String.join(",", METRICS) + "\n");
            for (Result r : rows) {
                double[] m = r.metrics();
                out.write(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%s,%s,%s,%s,%s,%.4f%n", r.run(), r.user(), r.relevant(), r.listed(), r.hits(),
                        m[0], m[1], m[2], m[3], m[4], m[5]));
            }
        }
        Document summary = new Document("uri", uri).append("k", k).append("truncate", truncate)
                .append("eligible_readers", relevant.size()).append("evaluated_readers", readers.size()).append("runs", summarise(rows));
        String json = summary.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build());
        Files.writeString(output.resolve("e3_summary.json"), json, StandardCharsets.UTF_8);
        System.out.println(json);
    }
}
