package bookscope.tools;

import bookscope.Compare;
import bookscope.Explore;
import bookscope.Interests;
import bookscope.Recommend;
import bookscope.Store;
import bookscope.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.bson.Document;

/**
 * Run the same requests on two deployments and check that the results are identical.
 *
 * <p>Results are compared as canonical JSON (sorted keys, timing fields removed). Lists keep their
 * ranked order: every service breaks ties deterministically (score, then identifier), so equal
 * records must give the same order. The request set mixes readers and books stored on different
 * shards of the sharded deployment.
 *
 * <pre>
 *   java -jar target/bookscope.jar compare-deployments --a mongodb://localhost:27100 --b mongodb://localhost:27200
 * </pre>
 */
public final class CompareDeployments {
    // Readers below 35004 are on shard0 and the others on shard1 with the default range placement.
    public static final Map<String, Function<Store, Document>> REQUESTS = new LinkedHashMap<>();

    static {
        REQUESTS.put("F1 explore 7", s -> Explore.explore(s, null, 7, 10, 5));
        REQUESTS.put("F1 explore 349", s -> Explore.explore(s, null, 349, 10, 5));
        REQUESTS.put("F1 ambiguous title", s -> Explore.explore(s, "the hobbit", null, 10, 5));
        REQUESTS.put("F2 same shard 32305/33065", s -> Compare.compare(s, 32305, 33065, null, null, 10));
        REQUESTS.put("F2 cross shard 32055/36002", s -> Compare.compare(s, 32055, 36002, null, null, 10));
        REQUESTS.put("F2 too few shared 34590/39865", s -> Compare.compare(s, 34590, 39865, null, null, 10));
        REQUESTS.put("F2 truncated 33065(5)/32305", s -> Compare.compare(s, 33065, 32305, 5, null, 10));
        REQUESTS.put("F3 shard0 reader 32055", s -> Recommend.recommend(s, 32055, 10, null));
        REQUESTS.put("F3 shard1 reader 36002", s -> Recommend.recommend(s, 36002, 10, null));
        REQUESTS.put("F3 truncated 32055 (5)", s -> Recommend.recommend(s, 32055, 10, 5));
        REQUESTS.put("F4 349 -> 7", s -> Trace.trace(s, 349, 7, 5));
        REQUESTS.put("F4 9139 -> 2491", s -> Trace.trace(s, 9139, 2491, 5));
        REQUESTS.put("F5 vampires/romance", s -> Interests.interests(s, "vampires", "romance", 5, 20));
        REQUESTS.put("F5 fantasy/science-fiction", s -> Interests.interests(s, "fantasy", "science-fiction", 5, 20));
    }

    private CompareDeployments() {
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> {
                if (!"elapsed_s".equals(k)) {
                    sorted.put(String.valueOf(k), canonicalValue(v));
                }
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(canonicalValue(v)));
            return out;
        }
        return value;
    }

    private static String canonical(Document result) {
        return String.valueOf(canonicalValue(result));
    }

    public static boolean run(Args args) {
        int failures = 0;
        try (Store a = new Store(args.get("--a", "mongodb://localhost:27100"));
             Store b = new Store(args.get("--b", "mongodb://localhost:27200"))) {
            for (Map.Entry<String, Function<Store, Document>> request : REQUESTS.entrySet()) {
                String ra = canonical(request.getValue().apply(a));
                String rb = canonical(request.getValue().apply(b));
                if (ra.equals(rb)) {
                    System.out.println("IDENTICAL  " + request.getKey());
                } else {
                    failures++;
                    int pos = 0;
                    while (pos < Math.min(ra.length(), rb.length()) && ra.charAt(pos) == rb.charAt(pos)) {
                        pos++;
                    }
                    System.out.printf("DIFFERENT  %s (first difference at character %d)%n  a: %s%n  b: %s%n", request.getKey(), pos,
                            ra.substring(Math.max(0, pos - 60), Math.min(ra.length(), pos + 60)),
                            rb.substring(Math.max(0, pos - 60), Math.min(rb.length(), pos + 60)));
                }
            }
        }
        System.out.printf("%d/%d requests identical%n", REQUESTS.size() - failures, REQUESTS.size());
        return failures == 0;
    }
}
