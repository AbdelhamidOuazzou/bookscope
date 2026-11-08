package bookscope.tools;

import bookscope.Interests;
import bookscope.Store;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * E4: a difficult case for F5.
 *
 * <p>For dystopia and young-adult, hundreds of books carry both tags, yet the reader-based measure
 * reports no link. The books carrying both tags are left out of both sides, but their likes were
 * still counted in each reader's total, which lowers the lift of two interests that share many
 * books. The run measures the same pairs with the original rule and with the shared books removed
 * from the totals too, to see whether the case improves and what changes for other pairs.
 *
 * <pre>
 *   java -jar target/bookscope.jar e4 --uri mongodb://localhost:27100 --output results
 * </pre>
 */
public final class ExperimentE4 {
    /** The difficult case first, then pairs used as controls. */
    private static final List<String[]> PAIRS = List.of(
            new String[] {"dystopia", "young-adult"},
            new String[] {"vampires", "romance"},
            new String[] {"fantasy", "science-fiction"},
            new String[] {"horror", "thriller"},
            new String[] {"mystery", "thriller"},
            new String[] {"historical-fiction", "romance"},
            new String[] {"classics", "fantasy"},
            new String[] {"paranormal", "romance"},
            new String[] {"science-fiction", "dystopia"},
            new String[] {"young-adult", "fantasy"},
            new String[] {"horror", "vampires"},
            new String[] {"romance", "contemporary"},
            new String[] {"humor", "chick-lit"},
            new String[] {"biography", "history"});

    private ExperimentE4() {
    }

    private static double num(Document d, String... path) {
        Object cur = d;
        for (String key : path) {
            if (!(cur instanceof Document doc) || doc.get(key) == null) {
                return Double.NaN;
            }
            cur = doc.get(key);
        }
        return ((Number) cur).doubleValue();
    }

    public static void run(Args args) throws IOException {
        Path output = Path.of(args.get("--output", "results"));
        Files.createDirectories(output);
        String uri = args.get("--uri", Store.DEFAULT_URI);
        StringBuilder csv = new StringBuilder(
                "tag_a,tag_b,shared_rule,status,books_a,books_b,books_in_both,attached_a,attached_b,bridge_readers,lift_full,lift_discounted,verdict\n");
        List<Document> summary = new ArrayList<>();
        try (Store store = new Store(uri)) {
            for (String[] pair : PAIRS) {
                for (boolean adjust : new boolean[] {false, true}) {
                    Document r;
                    try {
                        r = Interests.interests(store, pair[0], pair[1], 5, Interests.MIN_TAG_COUNT, adjust);
                    } catch (RuntimeException e) {
                        r = new Document("status", "error: " + e.getMessage());
                    }
                    String rule = adjust ? "shared_out_of_totals" : "original";
                    String status = r.getString("status");
                    csv.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%.0f,%.0f,%.0f,%.0f,%.0f,%.0f,%.3f,%.3f,\"%s\"%n", pair[0], pair[1], rule,
                            status.replace(',', ';'),
                            num(r, "interest_a", "books"), num(r, "interest_b", "books"), num(r, "books_in_both_total"),
                            num(r, "reader_groups", "attached_to_a"), num(r, "reader_groups", "attached_to_b"),
                            num(r, "reader_groups", "bridge_readers"), num(r, "reader_groups", "lift"),
                            num(r, "discounted", "lift"), String.valueOf(r.getString("verdict")).replace('"', '\'')));
                    summary.add(new Document("tag_a", pair[0]).append("tag_b", pair[1]).append("rule", rule).append("result", r));
                    System.out.printf(Locale.ROOT, "%-20s %-16s %-22s lift %.3f discounted %.3f%n", pair[0], pair[1], rule,
                            num(r, "reader_groups", "lift"), num(r, "discounted", "lift"));
                }
            }
        }
        Files.writeString(output.resolve("e4_raw.csv"), csv.toString(), StandardCharsets.UTF_8);
        Document out = new Document("uri", uri).append("runs", summary);
        Files.writeString(output.resolve("e4_summary.json"),
                out.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()), StandardCharsets.UTF_8);
        System.out.println("Wrote e4_raw.csv and e4_summary.json in " + output);
    }
}
