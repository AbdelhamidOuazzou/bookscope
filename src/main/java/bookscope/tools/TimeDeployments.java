package bookscope.tools;

import bookscope.Store;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bson.Document;

/**
 * Response time of the compare-deployments requests on several deployments.
 *
 * <p>For each deployment the catalogue is loaded first (it is cached by every running service), then
 * each request is run once to warm the caches and timed {@code --repeat} times; the median is kept.
 *
 * <pre>
 *   java -jar target/bookscope.jar time-deployments \
 *       --uris mongodb://localhost:27100,mongodb://localhost:27200,mongodb://localhost:27300 --output results
 * </pre>
 */
public final class TimeDeployments {
    private TimeDeployments() {
    }

    private static double medianMs(Store store, Function<Store, Document> request, int repeat) {
        request.apply(store);
        double[] t = new double[repeat];
        for (int i = 0; i < repeat; i++) {
            long start = System.nanoTime();
            request.apply(store);
            t[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(t);
        return repeat % 2 == 1 ? t[repeat / 2] : (t[repeat / 2 - 1] + t[repeat / 2]) / 2;
    }

    public static void run(Args args) throws Exception {
        List<String> uris = List.of(args.get("--uris", "mongodb://localhost:27100,mongodb://localhost:27200").split(","));
        int repeat = args.getInt("--repeat", 5);
        Path output = Path.of(args.get("--output", "results"));
        Files.createDirectories(output);
        Map<String, Function<Store, Document>> requests = CompareDeployments.REQUESTS;
        double[][] ms = new double[requests.size()][uris.size()];
        for (int j = 0; j < uris.size(); j++) {
            try (Store store = new Store(uris.get(j))) {
                store.catalog();
                int i = 0;
                for (Function<Store, Document> request : requests.values()) {
                    ms[i++][j] = medianMs(store, request, repeat);
                }
            }
        }
        List<String> names = new ArrayList<>(requests.keySet());
        try (BufferedWriter w = Files.newBufferedWriter(output.resolve("deployments_timing.csv"), StandardCharsets.UTF_8)) {
            w.write("request," + String.join(",", uris) + "\n");
            for (int i = 0; i < names.size(); i++) {
                StringBuilder line = new StringBuilder("\"" + names.get(i) + "\"");
                for (int j = 0; j < uris.size(); j++) {
                    line.append(String.format(Locale.ROOT, ",%.1f", ms[i][j]));
                }
                w.write(line + "\n");
            }
        }
        System.out.printf("%-32s", "median ms over " + repeat + " runs");
        uris.forEach(u -> System.out.printf("%18s", u.substring(u.lastIndexOf(':') + 1)));
        System.out.println();
        for (int i = 0; i < names.size(); i++) {
            System.out.printf("%-32s", names.get(i));
            for (int j = 0; j < uris.size(); j++) {
                System.out.printf(Locale.ROOT, "%18.1f", ms[i][j]);
            }
            System.out.println();
        }
        System.out.println("written " + output.resolve("deployments_timing.csv"));
    }
}
