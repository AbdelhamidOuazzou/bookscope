package bookscope;

import static bookscope.Util.LIKE;
import static bookscope.Util.cmp;
import static bookscope.Util.rnd;
import static bookscope.Util.toInt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;

/**
 * F5 - discover links between two reading interests (tags).
 *
 * <p>A book belongs to an interest when the tag is among its TOP_RANK most used content tags
 * (status tags such as to-read are skipped) and was given at least MIN_TAG_COUNT times.
 * Raw counts alone are misleading: 1984 carries "horror" 211 times because it is so popular.
 */
public final class Interests {
    public static final int MIN_TAG_COUNT = 20;
    public static final int TOP_RANK = 10;
    /** An attached reader liked >= 2 books of the interest... */
    public static final int MIN_PER_SIDE = 2;
    /** ... and their share of liked books in it is >= 2x the average share. */
    public static final double ATTACHMENT = 2.0;
    public static final double MIN_LIFT = 1.2;
    public static final int MIN_BRIDGE_LIKERS = 5;
    public static final double HUB_SHARE = 0.01;
    public static final double ACTIVE_QUANTILE = 0.95;

    private record Analysis(Document doc, double lift, Map<Integer, List<Integer>> bridge) {
    }

    private Interests() {
    }

    /**
     * How much readers attached to one interest also like the other one.
     *
     * <p>A reader is attached to an interest when at least MIN_PER_SIDE of their liked books belong
     * to it and their share of liked books in it is at least ATTACHMENT x the average share
     * (shares, not raw counts, so very active readers are not attached to everything).
     * Directional lift a->b: books of b liked by readers attached to a, divided by what their
     * likes outside a would contain if b had its average share there. Excluding a from the
     * expectation avoids a compositional bias (a horror fan has fewer likes left for romance).
     */
    private static Analysis analyse(Map<Integer, List<Integer>> groups, Map<Integer, Integer> likedTotal, Set<Integer> side1,
                                    Set<Integer> side2, Set<Integer> excludedBooks, Set<Integer> excludedReaders,
                                    Set<Integer> sharedOutOfTotals) {
        Map<Integer, int[]> counts = new HashMap<>();
        long sum1 = 0;
        long sum2 = 0;
        long total = 0;
        for (Map.Entry<Integer, Integer> entry : likedTotal.entrySet()) {
            int u = entry.getKey();
            if (excludedReaders.contains(u)) {
                continue;
            }
            int n1 = 0;
            int n2 = 0;
            int shared = 0;
            for (int b : groups.getOrDefault(u, List.of())) {
                if (!excludedBooks.contains(b)) {
                    n1 += side1.contains(b) ? 1 : 0;
                    n2 += side2.contains(b) ? 1 : 0;
                }
                shared += sharedOutOfTotals.contains(b) ? 1 : 0;
            }
            int liked = entry.getValue() - shared;
            counts.put(u, new int[] {n1, n2, liked});
            sum1 += n1;
            sum2 += n2;
            total += liked;
        }
        long totalOr1 = total != 0 ? total : 1;
        double base1 = (double) sum1 / totalOr1;
        double base2 = (double) sum2 / totalOr1;
        Set<Integer> att1 = new HashSet<>();
        Set<Integer> att2 = new HashSet<>();
        counts.forEach((u, c) -> {
            if (c[0] >= MIN_PER_SIDE && (double) c[0] / c[2] >= ATTACHMENT * base1) {
                att1.add(u);
            }
            if (c[1] >= MIN_PER_SIDE && (double) c[1] / c[2] >= ATTACHMENT * base2) {
                att2.add(u);
            }
        });

        double[] ab = directional(counts, att1, 0, 1, total - sum1, sum2);
        double[] ba = directional(counts, att2, 1, 0, total - sum2, sum1);
        double liftAb = ab[1] != 0 ? ab[0] / ab[1] : 0.0;
        double liftBa = ba[1] != 0 ? ba[0] / ba[1] : 0.0;
        Map<Integer, List<Integer>> bridge = new TreeMap<>();
        for (int u : att1) {
            if (att2.contains(u)) {
                bridge.put(u, groups.get(u));
            }
        }
        double lift = rnd(Math.sqrt(liftAb * liftBa), 3);
        Document doc = new Document("readers_considered", counts.size()).append("attached_to_a", att1.size())
                .append("attached_to_b", att2.size()).append("bridge_readers", bridge.size())
                .append("a_to_b", new Document("liked_b_books", (long) ab[0]).append("expected", rnd(ab[1], 1)).append("lift", rnd(liftAb, 3)))
                .append("b_to_a", new Document("liked_a_books", (long) ba[0]).append("expected", rnd(ba[1], 1)).append("lift", rnd(liftBa, 3)))
                .append("lift", lift);
        return new Analysis(doc, lift, bridge);
    }

    /** Returns {observed, expected}. */
    private static double[] directional(Map<Integer, int[]> counts, Set<Integer> attached, int mine, int other, long outside, long sumOther) {
        double q = (double) sumOther / (outside != 0 ? outside : 1);
        long observed = 0;
        long outsideAttached = 0;
        for (int u : attached) {
            int[] c = counts.get(u);
            observed += c[other];
            outsideAttached += c[2] - c[mine];
        }
        return new double[] {observed, outsideAttached * q};
    }

    private static List<Document> connectors(Catalog cat, Set<Integer> side, Map<Integer, Integer> tagCounts,
                                             Map<Integer, List<Integer>> bridge, Set<Integer> hubs, int k) {
        if (bridge.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> liked = new HashMap<>();
        for (List<Integer> books : bridge.values()) {
            for (int b : books) {
                if (side.contains(b)) {
                    liked.merge(b, 1, Integer::sum);
                }
            }
        }
        Map<Integer, Double> lift = new HashMap<>();
        liked.forEach((b, c) -> {
            if (c >= MIN_BRIDGE_LIKERS && cat.nPos(b) != 0) {
                lift.put(b, ((double) c / bridge.size()) / ((double) cat.nPos(b) / cat.nReaders));
            }
        });
        List<Integer> ranked = new ArrayList<>(lift.keySet());
        ranked.sort((x, y) -> {
            int c = cmp(rnd(lift.get(y) * Math.log(liked.get(y))), rnd(lift.get(x) * Math.log(liked.get(x))));
            return c != 0 ? c : Integer.compare(x, y);
        });
        List<Document> out = new ArrayList<>();
        for (int b : ranked.subList(0, Math.min(k, ranked.size()))) {
            out.add(cat.bookSummary(b).append("bridge_readers_who_liked", liked.get(b)).append("lift", rnd(lift.get(b), 3))
                    .append("tag_count", tagCounts.get(b)).append("hub", hubs.contains(b)));
        }
        return out;
    }

    /** Returns the tag, or fills early with the result to return. */
    private static Catalog.Tag resolveTag(Catalog cat, String name, Document[] early) {
        List<Catalog.Tag> matches = cat.findTags(name);
        if (matches.isEmpty()) {
            throw new BookScopeException("no tag matches \"" + name + "\"");
        }
        if (matches.size() > 1) {
            List<Document> shown = new ArrayList<>();
            for (Catalog.Tag t : matches.subList(0, Math.min(20, matches.size()))) {
                shown.add(new Document("tag_id", t.id()).append("name", t.name()).append("books", t.df()));
            }
            early[0] = new Document("service", "F5").append("status", "ambiguous").append("query", name).append("matches", shown)
                    .append("message", "several tags match; give the exact tag name");
            return null;
        }
        Catalog.Tag tag = matches.get(0);
        if (tag.statusLike()) {
            throw new BookScopeException("\"" + tag.name() + "\" describes a reading status or ownership, not a reading interest");
        }
        return tag;
    }

    public static Document interests(Store store, String tagA, String tagB, int k, int minCount) {
        return interests(store, tagA, tagB, k, minCount, false);
    }

    /**
     * @param adjustShared books carrying both tags are never counted on either side. When false (the
     *     original behaviour) their likes still count in each reader's total, which lowers the lift of
     *     two interests that share many books. When true they are removed from the totals too.
     */
    public static Document interests(Store store, String tagA, String tagB, int k, int minCount, boolean adjustShared) {
        Catalog cat = store.catalog();
        Document[] early = new Document[1];
        Catalog.Tag ta = resolveTag(cat, tagA, early);
        if (early[0] != null) {
            return early[0];
        }
        Catalog.Tag tb = resolveTag(cat, tagB, early);
        if (early[0] != null) {
            return early[0];
        }
        Map<Integer, Integer> countA = new HashMap<>();
        Map<Integer, Integer> countB = new HashMap<>();
        for (Catalog.Book book : cat.books.values()) {
            int rank = 0;
            for (Catalog.TagCount e : book.tags()) {
                if (cat.tags.get(e.tag()).statusLike()) {
                    continue;
                }
                if (++rank > TOP_RANK) {
                    break;
                }
                if (e.count() >= minCount && e.tag() == ta.id()) {
                    countA.put(book.id(), e.count());
                } else if (e.count() >= minCount && e.tag() == tb.id()) {
                    countB.put(book.id(), e.count());
                }
            }
        }
        Set<Integer> both = new HashSet<>(countA.keySet());
        both.retainAll(countB.keySet());
        Set<Integer> side1 = new HashSet<>(countA.keySet());
        side1.removeAll(both);
        Set<Integer> side2 = new HashSet<>(countB.keySet());
        side2.removeAll(both);
        if (countA.isEmpty() || countB.isEmpty()) {
            return new Document("service", "F5").append("status", "no_result")
                    .append("message", "an interest has no book tagged at least " + minCount + " times")
                    .append("books_interest_a", countA.size()).append("books_interest_b", countB.size());
        }

        Set<Integer> allBooks = new HashSet<>(countA.keySet());
        allBooks.addAll(countB.keySet());
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (Document d : store.db.getCollection("ratings").aggregate(List.of(
                new Document("$match", new Document("book_id", new Document("$in", new ArrayList<>(allBooks))).append("rating", new Document("$gte", LIKE))),
                new Document("$group", new Document("_id", "$user_id").append("books", new Document("$push", "$book_id"))))).allowDiskUse(true)) {
            List<Integer> books = new ArrayList<>(d.getList("books", Integer.class));
            books.sort(null);
            groups.put(toInt(d.get("_id")), books);
        }
        Map<Integer, Integer> likedTotal = new HashMap<>();
        for (Document d : store.db.getCollection("ratings").aggregate(List.of(
                new Document("$match", new Document("rating", new Document("$gte", LIKE))),
                new Document("$group", new Document("_id", "$user_id").append("n", new Document("$sum", 1))))).allowDiskUse(true)) {
            likedTotal.put(toInt(d.get("_id")), toInt(d.get("n")));
        }

        Set<Integer> sharedOut = adjustShared ? both : Set.of();
        Analysis full = analyse(groups, likedTotal, side1, side2, Set.of(), Set.of(), sharedOut);
        Set<Integer> hubs = cat.hubs(HUB_SHARE);
        List<Integer> activity = new ArrayList<>();
        for (Document d : store.db.getCollection("user_stats").find().projection(new Document("n", 1))) {
            activity.add(toInt(d.get("n")));
        }
        activity.sort(null);
        int threshold = activity.get((int) (ACTIVE_QUANTILE * (activity.size() - 1)));
        Set<Integer> veryActive = new HashSet<>();
        for (Document d : store.db.getCollection("user_stats").find(new Document("n", new Document("$gt", threshold)))
                .projection(new Document("_id", 1))) {
            veryActive.add(toInt(d.get("_id")));
        }
        Analysis discounted = analyse(groups, likedTotal, side1, side2, hubs, veryActive, sharedOut);

        List<Integer> bridgingBooks = new ArrayList<>(both);
        bridgingBooks.sort((x, y) -> {
            int c = Integer.compare(Math.min(countA.get(y), countB.get(y)), Math.min(countA.get(x), countB.get(x)));
            return c != 0 ? c : Integer.compare(x, y);
        });
        List<Document> inBoth = new ArrayList<>();
        for (int b : bridgingBooks.subList(0, Math.min(k, bridgingBooks.size()))) {
            long bridgeLikers = full.bridge().values().stream().filter(books -> books.contains(b)).count();
            inBoth.add(cat.bookSummary(b).append("tag_count_a", countA.get(b)).append("tag_count_b", countB.get(b))
                    .append("bridge_readers_who_liked", bridgeLikers));
        }
        boolean linked = full.lift() >= MIN_LIFT;
        boolean persists = linked && discounted.lift() >= MIN_LIFT;
        Set<Integer> side1NoHubs = new HashSet<>(side1);
        side1NoHubs.removeAll(hubs);
        Set<Integer> side2NoHubs = new HashSet<>(side2);
        side2NoHubs.removeAll(hubs);
        Document discountedDoc = new Document(discounted.doc()).append("excluded_hub_books", hubs.size())
                .append("excluded_readers", veryActive.size()).append("activity_threshold", threshold)
                .append("connecting_books_a", first3(connectors(cat, side1NoHubs, countA, discounted.bridge(), hubs, k)))
                .append("connecting_books_b", first3(connectors(cat, side2NoHubs, countB, discounted.bridge(), hubs, k)));
        String verdict = persists ? "the link persists without popular books and very active readers"
                : linked ? "the link disappears once popular books and very active readers are discounted"
                : "no link beyond chance: readers attached to one interest do not like the other more "
                + "than expected (lift < " + MIN_LIFT + ")";
        return new Document("service", "F5").append("status", "ok")
                .append("interest_a", new Document("tag", ta.name()).append("tag_id", ta.id()).append("books", countA.size()))
                .append("interest_b", new Document("tag", tb.name()).append("tag_id", tb.id()).append("books", countB.size()))
                .append("books_in_both", inBoth).append("books_in_both_total", both.size())
                .append("reader_groups", full.doc())
                .append("connecting_books_a", connectors(cat, side1, countA, full.bridge(), hubs, k))
                .append("connecting_books_b", connectors(cat, side2, countB, full.bridge(), hubs, k))
                .append("discounted", discountedDoc).append("verdict", verdict)
                .append("parameters", new Document("min_tag_count", minCount)
                        .append("tag_rank", "tag among the " + TOP_RANK + " most used content tags")
                        .append("attachment", ">= " + MIN_PER_SIDE + " liked books and >= " + ATTACHMENT + "x the average share")
                        .append("min_lift", MIN_LIFT).append("like_threshold", LIKE).append("shared_books_out_of_totals", adjustShared)
                        .append("hubs", "top 1% most rated books")
                        .append("very_active", "readers above the 95% activity quantile"));
    }

    private static List<Document> first3(List<Document> docs) {
        return docs.subList(0, Math.min(3, docs.size()));
    }
}
