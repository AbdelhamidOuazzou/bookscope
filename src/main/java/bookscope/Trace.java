package bookscope;

import static bookscope.Util.cmp;
import static bookscope.Util.rnd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;

/** F4 - trace a connection between two books. */
public final class Trace {
    public static final int MIN_SHARED = 5;
    public static final double MIN_LIFT = 1.2;
    /** The 1% most rated books are hubs: paths through them say little. */
    public static final double HUB_SHARE = 0.01;

    private Trace() {
    }

    public static Document trace(Store store, int bookA, int bookB, int k) {
        Catalog cat = store.catalog();
        for (int b : List.of(bookA, bookB)) {
            if (!cat.books.containsKey(b)) {
                throw new BookScopeException("unknown book_id " + b);
            }
        }
        if (bookA == bookB) {
            throw new BookScopeException("the two books are identical");
        }
        Explore.ReaderLinks a = Explore.readerLinks(store, bookA, MIN_SHARED);
        Explore.ReaderLinks b = Explore.readerLinks(store, bookB, MIN_SHARED);
        Set<Integer> hubs = cat.hubs(HUB_SHARE);

        // 1. Direct: readers who liked both books. Each reader is down-weighted by activity so that
        //    a handful of very active readers cannot create the link alone.
        Set<Integer> shared = new TreeSet<>(a.likers());
        shared.retainAll(new HashSet<>(b.likers()));
        List<Integer> both = new ArrayList<>(shared);
        Map<Integer, Integer> activity = store.activity(both);
        double expected = cat.nReaders != 0 ? (double) a.likers().size() * b.likers().size() / cat.nReaders : 0;
        double directLift = expected != 0 ? both.size() / expected : 0.0;
        double support = 0;
        for (int u : both) {
            support += 1 / (Math.log(1 + activity.getOrDefault(u, 2)) / Math.log(2));
        }
        List<Integer> byActivity = new ArrayList<>(both);
        byActivity.sort((x, y) -> {
            int c = Integer.compare(activity.getOrDefault(x, 0), activity.getOrDefault(y, 0));
            return c != 0 ? c : Integer.compare(x, y);
        });
        List<Document> examples = new ArrayList<>();
        for (int u : byActivity.subList(0, Math.min(5, byActivity.size()))) {
            examples.add(new Document("user_id", u).append("ratings", activity.get(u)));
        }
        Document direct = new Document("shared_readers", both.size()).append("expected_if_independent", rnd(expected, 2))
                .append("lift", rnd(directLift, 3)).append("activity_weighted_support", rnd(support, 3))
                .append("example_readers", examples);
        boolean directOk = both.size() >= MIN_SHARED && directLift >= MIN_LIFT;

        // 2. Two hops through an intermediate book liked with both ends.
        record Path(double score, int via, Explore.Link la, Explore.Link lb) {
        }
        List<Path> paths = new ArrayList<>();
        a.links().forEach((c, la) -> {
            Explore.Link lb = b.links().get(c);
            if (lb != null && c != bookA && c != bookB && la.enough() && lb.enough()) {
                paths.add(new Path(la.cosine() * lb.cosine() * (hubs.contains(c) ? 0.5 : 1.0), c, la, lb));
            }
        });
        paths.sort((x, y) -> {
            int c = cmp(rnd(y.score()), rnd(x.score()));
            return c != 0 ? c : Integer.compare(x.via(), y.via());
        });
        List<Document> alternatives = new ArrayList<>();
        boolean informative = false;
        for (Path p : paths.subList(0, Math.min(k, paths.size()))) {
            boolean hub = hubs.contains(p.via());
            informative |= !hub && p.la().lift() >= MIN_LIFT && p.lb().lift() >= MIN_LIFT;
            alternatives.add(new Document("via", cat.bookSummary(p.via())).append("score", rnd(p.score())).append("hub", hub)
                    .append("edge_a", new Document("shared_readers", p.la().sharedReaders()).append("lift", p.la().lift()))
                    .append("edge_b", new Document("shared_readers", p.lb().sharedReaders()).append("lift", p.lb().lift())));
        }

        // 3. Catalogue attributes.
        Set<String> authorSet = new TreeSet<>(cat.books.get(bookA).authors());
        authorSet.retainAll(cat.books.get(bookB).authors());
        List<String> authors = new ArrayList<>(authorSet);
        List<Document> tags = cat.sharedTags(bookA, bookB, 0);
        List<Document> specific = tags.stream().filter(t -> !t.getBoolean("generic")).limit(5).toList();

        String verdict;
        if (directOk) {
            verdict = "direct connection: the books share more readers than expected by chance";
        } else if (informative) {
            verdict = "indirect connection through books liked by readers of both";
        } else if (!authors.isEmpty() || !specific.isEmpty()) {
            verdict = "catalogue connection only (author or specific tags), no convincing reader evidence";
        } else {
            List<String> reasons = new ArrayList<>();
            if (both.size() < MIN_SHARED) {
                reasons.add("only " + both.size() + " reader(s) liked both books (minimum " + MIN_SHARED + ")");
            } else if (directLift < MIN_LIFT) {
                reasons.add(String.format(Locale.ROOT, "shared readers are not above chance (lift %.2f)", directLift));
            }
            reasons.add("no intermediate book outside the most popular hubs links them");
            if (!tags.isEmpty()) {
                reasons.add("the only shared tags are generic: "
                        + String.join(", ", tags.stream().limit(3).map(t -> t.getString("tag")).toList()));
            }
            verdict = "no convincing connection: " + String.join("; ", reasons);
        }
        boolean connected = directOk || informative || !authors.isEmpty() || !specific.isEmpty();
        return new Document("service", "F4").append("status", connected ? "ok" : "no_connection")
                .append("book_a", cat.bookSummary(bookA).append("readers_who_liked", a.likers().size()))
                .append("book_b", cat.bookSummary(bookB).append("readers_who_liked", b.likers().size()))
                .append("verdict", verdict).append("direct", direct).append("two_hop_paths", alternatives)
                .append("catalogue", new Document("shared_authors", authors).append("shared_specific_tags", specific)
                        .append("shared_generic_tags", tags.stream().filter(t -> t.getBoolean("generic")).limit(5)
                                .map(t -> t.getString("tag")).toList()))
                .append("parameters", new Document("min_shared_readers", MIN_SHARED).append("min_lift", MIN_LIFT)
                        .append("hub_books", "top 1% most rated, path score halved"));
    }
}
