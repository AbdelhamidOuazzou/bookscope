package bookscope;

import static bookscope.Util.cmp;
import static bookscope.Util.rnd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;

/** F2 - compare two reader profiles. */
public final class Compare {
    public static final int MIN_COMMON = 5;
    /** Shared books needed before the similarity is used at full strength. */
    public static final int FULL_CONFIDENCE = 25;

    private record Ranked(double key, int book) {
    }

    private Compare() {
    }

    public static Document compare(Store store, int userA, int userB, Integer truncateA, Integer truncateB, int k) {
        Catalog cat = store.catalog();
        List<Store.Rating> ha = store.history(userA, truncateA);
        List<Store.Rating> hb = store.history(userB, truncateB);
        if (ha.isEmpty() || hb.isEmpty()) {
            int missing = ha.isEmpty() ? userA : userB;
            return new Document("service", "F2").append("status", "not_found")
                    .append("message", "reader " + missing + " has no stored ratings");
        }
        Map<Integer, Integer> ra = toMap(ha);
        Map<Integer, Integer> rb = toMap(hb);
        double meanA = mean(ra);
        double meanB = mean(rb);
        Set<Integer> common = new TreeSet<>(ra.keySet());
        common.retainAll(rb.keySet());
        Set<Integer> union = new HashSet<>(ra.keySet());
        union.addAll(rb.keySet());

        List<Document> readers = List.of(
                new Document("user_id", userA).append("ratings", ra.size()).append("mean_rating", rnd(meanA, 3)).append("truncated_to", truncateA),
                new Document("user_id", userB).append("ratings", rb.size()).append("mean_rating", rnd(meanB, 3)).append("truncated_to", truncateB));
        Document overlap = new Document("shared_books", common.size())
                .append("jaccard", rnd((double) common.size() / union.size()))
                .append("share_of_smaller_history", rnd((double) common.size() / Math.min(ra.size(), rb.size())));

        if (common.size() < MIN_COMMON) {
            List<Document> shared = new ArrayList<>();
            for (int b : common) {
                shared.add(row(cat, ra, rb, b));
            }
            return new Document("service", "F2").append("status", "insufficient_overlap").append("readers", readers)
                    .append("overlap", overlap)
                    .append("message", "the readers share " + common.size() + " book(s); at least " + MIN_COMMON
                            + " are needed for a meaningful comparison")
                    .append("shared_books", shared);
        }

        // Ratings are centred on each reader's mean (harsh vs generous raters) and every book is
        // weighted by inverse popularity, so agreeing on a bestseller counts less than on a rare book.
        double num = 0;
        double denA = 0;
        double denB = 0;
        for (int b : common) {
            double w = cat.bookWeight(b);
            double da = ra.get(b) - meanA;
            double db = rb.get(b) - meanB;
            num += w * da * db;
            denA += w * da * da;
            denB += w * db * db;
        }
        double correlation = denA != 0 && denB != 0 ? num / Math.sqrt(denA * denB) : 0.0;
        double confidence = (double) Math.min(common.size(), FULL_CONFIDENCE) / FULL_CONFIDENCE;
        double strength = correlation * confidence;

        List<Ranked> agreements = new ArrayList<>();
        List<Ranked> disagreements = new ArrayList<>();
        for (int b : common) {
            int gap = Math.abs(ra.get(b) - rb.get(b));
            double da = ra.get(b) - meanA;
            double db = rb.get(b) - meanB;
            double w = cat.bookWeight(b);
            if (da * db > 0 || gap == 0) {
                agreements.add(new Ranked(-(w * (1 + Math.min(Math.abs(da), Math.abs(db)))), b));
            }
            if (da * db < 0 || gap >= 2) {
                disagreements.add(new Ranked(-(w * gap), b));
            }
        }
        String label = strength >= 0.5 ? "strong" : strength >= 0.25 ? "moderate" : strength > -0.25 ? "weak" : "opposed";
        return new Document("service", "F2").append("status", "ok").append("readers", readers).append("overlap", overlap)
                .append("similarity", new Document("weighted_correlation", rnd(correlation)).append("confidence", rnd(confidence))
                        .append("strength", rnd(strength)).append("label", label))
                .append("agreements", top(agreements, k, cat, ra, rb))
                .append("disagreements", top(disagreements, k, cat, ra, rb))
                .append("method", "popularity-weighted Pearson correlation of mean-centred ratings on shared books, "
                        + "scaled by min(shared, " + FULL_CONFIDENCE + ")/" + FULL_CONFIDENCE);
    }

    private static Map<Integer, Integer> toMap(List<Store.Rating> history) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Store.Rating r : history) {
            out.put(r.bookId(), r.rating());
        }
        return out;
    }

    private static double mean(Map<Integer, Integer> ratings) {
        long sum = 0;
        for (int r : ratings.values()) {
            sum += r;
        }
        return (double) sum / ratings.size();
    }

    private static Document row(Catalog cat, Map<Integer, Integer> ra, Map<Integer, Integer> rb, int b) {
        return new Document("book_id", b).append("title", cat.title(b)).append("rating_a", ra.get(b)).append("rating_b", rb.get(b))
                .append("readers_of_book", cat.n(b)).append("weight", rnd(cat.bookWeight(b), 4));
    }

    private static List<Document> top(List<Ranked> ranked, int k, Catalog cat, Map<Integer, Integer> ra, Map<Integer, Integer> rb) {
        ranked.sort((x, y) -> {
            int c = cmp(x.key(), y.key());
            return c != 0 ? c : Integer.compare(x.book(), y.book());
        });
        List<Document> out = new ArrayList<>();
        for (Ranked r : ranked.subList(0, Math.min(k, ranked.size()))) {
            out.add(row(cat, ra, rb, r.book()));
        }
        return out;
    }
}
