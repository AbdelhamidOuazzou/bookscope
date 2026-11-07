package bookscope.tools;

import bookscope.Compare;
import bookscope.Store;
import bookscope.Trace;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;

/**
 * Independent verification of F2 and F4 results from the original CSV files.
 *
 * <p>The computation never reads the database and uses no BookScope service: it re-implements the
 * selection rule, the evaluation hold-out and the two metrics on the raw ratings.csv. It assumes
 * the stored state is "prepared data + update batch imported once" (the update batch only gives
 * back ratings withheld from the same CSV).
 *
 * <pre>
 *   java -jar target/bookscope.jar verify-csv --f2 32055 36002 --f4 349 7
 *   java -jar target/bookscope.jar verify-csv --f2 32055 36002 --f4 349 7 --check mongodb://localhost:27100
 * </pre>
 *
 * <p>With --check, the BookScope service is run on the given deployment afterwards and the two
 * sets of numbers are compared.
 */
public final class VerifyCsv {
    private static final int LIKE = 4;

    private VerifyCsv() {
    }

    private static boolean inEvalSet(int userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(("bookscope:" + userId).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).mod(BigInteger.valueOf(100)).intValue() < 20;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** user_id -> [(book_id, rating)] as stored after the evaluation hold-out. */
    private static Map<Integer, List<int[]>> storedRatings(Path raw, int first, int last, int evalLast) throws IOException {
        Map<Integer, List<int[]>> history = new HashMap<>();
        Csv.rows(raw.resolve("ratings.csv"), fields -> {
            int u = Integer.parseInt(fields[0]);
            if (first <= u && u <= last) {
                history.computeIfAbsent(u, x -> new ArrayList<>()).add(new int[] {Integer.parseInt(fields[1]), Integer.parseInt(fields[2])});
            }
        });
        history.forEach((u, rows) -> {
            if (inEvalSet(u)) {
                rows.subList(rows.size() - evalLast, rows.size()).clear();
            }
        });
        return history;
    }

    private static Map<String, Object> f2(Map<Integer, List<int[]>> history, int a, int b) {
        int nReaders = history.size();
        Map<Integer, Integer> nBook = new HashMap<>();
        history.values().forEach(rows -> rows.forEach(r -> nBook.merge(r[0], 1, Integer::sum)));
        Map<Integer, Integer> ra = new HashMap<>();
        Map<Integer, Integer> rb = new HashMap<>();
        history.get(a).forEach(r -> ra.put(r[0], r[1]));
        history.get(b).forEach(r -> rb.put(r[0], r[1]));
        double meanA = ra.values().stream().mapToInt(i -> i).sum() / (double) ra.size();
        double meanB = rb.values().stream().mapToInt(i -> i).sum() / (double) rb.size();
        Set<Integer> common = new TreeSet<>(ra.keySet());
        common.retainAll(rb.keySet());
        double num = 0;
        double denA = 0;
        double denB = 0;
        for (int book : common) {
            double w = Math.log((nReaders + 1.0) / (nBook.get(book) + 1.0));
            double da = ra.get(book) - meanA;
            double db = rb.get(book) - meanB;
            num += w * da * db;
            denA += w * da * da;
            denB += w * db * db;
        }
        double corr = denA != 0 && denB != 0 ? num / Math.sqrt(denA * denB) : 0.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shared_books", common.size());
        out.put("weighted_correlation", round(corr, 6));
        out.put("strength", round(corr * Math.min(common.size(), 25) / 25, 6));
        return out;
    }

    private static Set<Integer> likers(Map<Integer, List<int[]>> history, int book) {
        Set<Integer> out = new HashSet<>();
        history.forEach((u, rows) -> {
            for (int[] r : rows) {
                if (r[0] == book && r[1] >= LIKE) {
                    out.add(u);
                }
            }
        });
        return out;
    }

    private static Map<String, Object> f4(Map<Integer, List<int[]>> history, int a, int b) {
        Set<Integer> likersA = likers(history, a);
        Set<Integer> likersB = likers(history, b);
        Set<Integer> both = new HashSet<>(likersA);
        both.retainAll(likersB);
        double expected = (double) likersA.size() * likersB.size() / history.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readers_who_liked_a", likersA.size());
        out.put("readers_who_liked_b", likersB.size());
        out.put("shared_readers", both.size());
        out.put("expected_if_independent", round(expected, 2));
        out.put("lift", round(both.size() / expected, 3));
        return out;
    }

    public static boolean run(Args args) throws IOException {
        int[] range = args.getPair("--readers", 30000, 39999);
        int[] pairF2 = args.getPair("--f2", 32055, 36002);
        int[] pairF4 = args.getPair("--f4", 349, 7);
        Map<Integer, List<int[]>> history = storedRatings(Path.of(args.get("--raw", "data/raw")), range[0], range[1], args.getInt("--eval-last", 3));
        Map<String, Object> v2 = f2(history, pairF2[0], pairF2[1]);
        Map<String, Object> v4 = f4(history, pairF4[0], pairF4[1]);
        System.out.println("F2 from CSV: " + v2);
        System.out.println("F4 from CSV: " + v4);
        if (!args.has("--check")) {
            return true;
        }
        try (Store store = new Store(args.get("--check", null))) {
            Document s2 = Compare.compare(store, pairF2[0], pairF2[1], null, null, 10);
            Document s4 = Trace.trace(store, pairF4[0], pairF4[1], 5);
            Document similarity = s2.get("similarity", new Document());
            Map<String, Object> got2 = new LinkedHashMap<>();
            got2.put("shared_books", s2.get("overlap", Document.class).get("shared_books"));
            got2.put("weighted_correlation", similarity.get("weighted_correlation"));
            got2.put("strength", similarity.get("strength"));
            Document direct = s4.get("direct", Document.class);
            Map<String, Object> got4 = new LinkedHashMap<>();
            got4.put("readers_who_liked_a", s4.get("book_a", Document.class).get("readers_who_liked"));
            got4.put("readers_who_liked_b", s4.get("book_b", Document.class).get("readers_who_liked"));
            got4.put("shared_readers", direct.get("shared_readers"));
            got4.put("expected_if_independent", direct.get("expected_if_independent"));
            got4.put("lift", direct.get("lift"));
            boolean ok2 = v2.equals(got2);
            boolean ok4 = v4.equals(got4);
            System.out.println("F2: " + (ok2 ? "MATCH" : "MISMATCH\n  csv:     " + v2 + "\n  service: " + got2));
            System.out.println("F4: " + (ok4 ? "MATCH" : "MISMATCH\n  csv:     " + v4 + "\n  service: " + got4));
            return ok2 && ok4;
        }
    }
}
