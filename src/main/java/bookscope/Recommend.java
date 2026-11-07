package bookscope;

import static bookscope.Util.LIKE;
import static bookscope.Util.cmp;
import static bookscope.Util.rnd;
import static bookscope.Util.toInt;

import com.mongodb.client.model.Filters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;

/** F3 - recommend books a reader has not rated. */
public final class Recommend {
    public static final int NEIGHBOURS = 50;
    public static final int CANDIDATE_NEIGHBOURS = 1000;
    public static final int MIN_SUPPORT = 2;
    public static final double POPULARITY_PENALTY = 0.35;
    /** Below this many ratings, reader evidence is blended with tag evidence. */
    public static final int SHORT_HISTORY = 10;
    public static final double TAG_SHARE = 0.5;

    private record Neighbour(int userId, double similarity, List<Integer> books) {
    }

    private record Support(int userId, int rating) {
    }

    private Recommend() {
    }

    /** Readers who liked the same books, weighted by book rarity and normalised by activity. */
    private static List<Neighbour> findNeighbours(Store store, int userId, List<Integer> liked) {
        Catalog cat = store.catalog();
        List<Document> pipeline = List.of(
                new Document("$match", new Document("book_id", new Document("$in", liked))
                        .append("rating", new Document("$gte", LIKE)).append("user_id", new Document("$ne", userId))),
                new Document("$group", new Document("_id", "$user_id").append("books", new Document("$push", "$book_id"))));
        record Raw(double score, int userId, List<Integer> books) {
        }
        List<Raw> raw = new ArrayList<>();
        for (Document d : store.db.getCollection("ratings").aggregate(pipeline).allowDiskUse(true)) {
            List<Integer> books = new ArrayList<>(d.getList("books", Integer.class));
            books.sort(null);
            double sum = 0;
            for (int b : books) {
                sum += cat.bookWeight(b);
            }
            raw.add(new Raw(sum, toInt(d.get("_id")), books));
        }
        raw.sort((x, y) -> {
            int c = cmp(y.score(), x.score());
            return c != 0 ? c : Integer.compare(x.userId(), y.userId());
        });
        raw = raw.subList(0, Math.min(CANDIDATE_NEIGHBOURS, raw.size()));
        Map<Integer, Integer> activity = store.activity(raw.stream().map(Raw::userId).toList());
        // Very active readers share many books with everyone: divide by sqrt(their activity).
        List<Raw> scored = new ArrayList<>();
        for (Raw r : raw) {
            scored.add(new Raw(r.score() / Math.sqrt(activity.getOrDefault(r.userId(), 1)), r.userId(), r.books()));
        }
        scored.sort((x, y) -> {
            int c = cmp(y.score(), x.score());
            return c != 0 ? c : Integer.compare(x.userId(), y.userId());
        });
        List<Neighbour> out = new ArrayList<>();
        for (Raw r : scored.subList(0, Math.min(NEIGHBOURS, scored.size()))) {
            out.add(new Neighbour(r.userId(), rnd(r.score()), r.books()));
        }
        return out;
    }

    public static Document recommend(Store store, int userId, int k, Integer truncate) {
        return recommend(store, userId, k, truncate, POPULARITY_PENALTY, TAG_SHARE);
    }

    public static Document recommend(Store store, int userId, int k, Integer truncate, double penalty, double tagShare) {
        Catalog cat = store.catalog();
        List<Store.Rating> history = store.history(userId, truncate);
        if (history.isEmpty()) {
            return new Document("service", "F3").append("status", "not_found")
                    .append("message", "reader " + userId + " has no stored ratings");
        }
        Set<Integer> rated = new HashSet<>();
        List<Integer> liked = new ArrayList<>();
        int top = 0;
        for (Store.Rating r : history) {
            rated.add(r.bookId());
            top = Math.max(top, r.rating());
            if (r.rating() >= LIKE) {
                liked.add(r.bookId());
            }
        }
        String basisNote = null;
        if (liked.isEmpty()) {
            for (Store.Rating r : history) {
                if (r.rating() == top) {
                    liked.add(r.bookId());
                }
            }
            basisNote = "no rating >= " + LIKE + "; the reader's highest-rated books (" + top + ") are used instead";
        }
        liked.sort(null);

        List<Neighbour> neighbours = findNeighbours(store, userId, liked);
        Map<Integer, Double> sims = new LinkedHashMap<>();
        Map<Integer, List<Integer>> sharedBooks = new HashMap<>();
        for (Neighbour n : neighbours) {
            sims.put(n.userId(), n.similarity());
            sharedBooks.put(n.userId(), n.books());
        }
        Map<Integer, List<Support>> support = new HashMap<>();
        if (!neighbours.isEmpty()) {
            for (Document r : store.db.getCollection("ratings")
                    .find(Filters.and(Filters.in("user_id", sims.keySet()), Filters.gte("rating", LIKE)))
                    .projection(new Document("_id", 0).append("user_id", 1).append("book_id", 1).append("rating", 1))) {
                int book = toInt(r.get("book_id"));
                if (!rated.contains(book)) {
                    support.computeIfAbsent(book, x -> new ArrayList<>()).add(new Support(toInt(r.get("user_id")), toInt(r.get("rating"))));
                }
            }
        }

        Map<Integer, Double> readerScores = new HashMap<>();
        support.forEach((b, rs) -> {
            if (rs.size() >= MIN_SUPPORT) {
                double sum = 0;
                for (Support s : rs) {
                    sum += sims.get(s.userId());
                }
                readerScores.put(b, sum / Math.pow(cat.nPos(b) == 0 ? 1 : cat.nPos(b), penalty));
            }
        });

        boolean isShort = history.size() < SHORT_HISTORY;
        Map<Integer, Double> profile = new LinkedHashMap<>();
        for (int b : liked) {
            cat.tagVectors().get(b).forEach((t, w) -> profile.merge(t, w, Double::sum));
        }
        double squares = 0;
        for (double w : profile.values()) {
            squares += w * w;
        }
        double norm = squares > 0 ? Math.sqrt(squares) : 1.0;
        profile.replaceAll((t, w) -> w / norm);
        Map<Integer, Double> tagScores = cat.tagSimilar(profile, rated);

        Map<Integer, Double> finalScores;
        String mode;
        if (isShort) {
            // Few ratings: few, weakly supported neighbours. Blend in the reader's tag profile.
            Map<Integer, Double> rn = normalise(readerScores);
            Map<Integer, Double> tn = normalise(tagScores);
            Set<Integer> union = new HashSet<>(rn.keySet());
            union.addAll(tn.keySet());
            finalScores = new HashMap<>();
            for (int b : union) {
                finalScores.put(b, (1 - tagShare) * rn.getOrDefault(b, 0.0) + tagShare * tn.getOrDefault(b, 0.0));
            }
            mode = "short history (< " + SHORT_HISTORY + " ratings): " + Math.round((1 - tagShare) * 100)
                    + "% similar readers + " + Math.round(tagShare * 100) + "% tag profile";
        } else {
            finalScores = readerScores;
            mode = "similar readers (user-based collaborative filtering)";
        }
        List<Integer> ranked = new ArrayList<>(finalScores.keySet());
        ranked.sort((x, y) -> {
            int c = cmp(rnd(finalScores.get(y)), rnd(finalScores.get(x)));
            return c != 0 ? c : Integer.compare(x, y);
        });
        ranked = ranked.subList(0, Math.min(k, ranked.size()));

        List<Document> results = new ArrayList<>();
        List<Integer> pops = new ArrayList<>();
        int inTop100 = 0;
        int rank = 0;
        for (int b : ranked) {
            rank++;
            List<Support> rs = new ArrayList<>(support.getOrDefault(b, List.of()));
            rs.sort((x, y) -> {
                int c = cmp(sims.get(y.userId()), sims.get(x.userId()));
                return c != 0 ? c : Integer.compare(x.userId(), y.userId());
            });
            Map<Integer, Double> vector = cat.tagVectors().get(b);
            List<Integer> tagOrder = new ArrayList<>(vector.keySet());
            tagOrder.sort((x, y) -> cmp(-profile.getOrDefault(x, 0.0) * vector.get(x), -profile.getOrDefault(y, 0.0) * vector.get(y)));
            List<String> bestShared = new ArrayList<>();
            for (int t : tagOrder) {
                if (profile.containsKey(t)) {
                    bestShared.add(cat.tags.get(t).name());
                }
                if (bestShared.size() == 3) {
                    break;
                }
            }
            List<Document> supporters = new ArrayList<>();
            for (Support s : rs.subList(0, Math.min(3, rs.size()))) {
                List<Integer> shared = sharedBooks.get(s.userId());
                supporters.add(new Document("user_id", s.userId()).append("rating", s.rating())
                        .append("also_liked_your_books", new ArrayList<>(shared.subList(0, Math.min(3, shared.size())))));
            }
            List<String> explanation = new ArrayList<>();
            if (!rs.isEmpty()) {
                explanation.add("liked by " + rs.size() + " of your " + neighbours.size() + " most similar readers");
            }
            if (!bestShared.isEmpty()) {
                explanation.add("shares tags " + String.join(", ", bestShared) + " with books you liked");
            }
            boolean popular = cat.top100().contains(b);
            inTop100 += popular ? 1 : 0;
            pops.add(cat.n(b));
            Document result = new Document("rank", rank);
            result.putAll(cat.bookSummary(b));
            results.add(result.append("score", rnd(finalScores.get(b)))
                    .append("supporting_readers", rs.size()).append("tag_similarity", rnd(tagScores.getOrDefault(b, 0.0)))
                    .append("in_top100_most_rated", popular)
                    .append("explanation", String.join("; ", explanation)).append("evidence", supporters));
        }

        Document out = new Document("service", "F3").append("status", results.isEmpty() ? "no_result" : "ok")
                .append("reader", new Document("user_id", userId).append("ratings_used", history.size())
                        .append("liked_books_used", liked.size()).append("truncated_to", truncate)
                        .append("history_note", truncate != null && truncate != 0
                                ? "TRUNCATED: only the " + truncate + " earliest ratings are used" : null))
                .append("mode", mode).append("neighbours_used", neighbours.size()).append("recommendations", results)
                .append("popularity", new Document("share_in_top100_most_rated",
                        results.isEmpty() ? null : rnd((double) inTop100 / results.size(), 3))
                        .append("median_ratings_of_recommended", pops.isEmpty() ? null : Util.median(pops))
                        .append("median_ratings_of_top100", Util.median(counts(cat, cat.top100())))
                        .append("median_ratings_of_catalogue", Util.median(counts(cat, cat.books.keySet()))))
                .append("parameters", new Document("k", k).append("neighbours", NEIGHBOURS).append("min_support", MIN_SUPPORT)
                        .append("popularity_penalty", penalty).append("like_threshold", LIKE));
        if (basisNote != null) {
            out.append("note", basisNote);
        }
        if (results.isEmpty()) {
            out.append("message", "not enough connected evidence to recommend anything");
        }
        return out;
    }

    private static Map<Integer, Double> normalise(Map<Integer, Double> scores) {
        double top = 0;
        for (double s : scores.values()) {
            top = Math.max(top, s);
        }
        double divisor = top != 0 ? top : 1.0;
        Map<Integer, Double> out = new HashMap<>();
        scores.forEach((b, s) -> out.put(b, s / divisor));
        return out;
    }

    private static List<Integer> counts(Catalog cat, Collection<Integer> books) {
        return books.stream().map(cat::n).toList();
    }

    /** Most rated books the reader has not rated (baseline for E3). */
    public static List<Integer> popularityBaseline(Catalog cat, Set<Integer> known, int k) {
        return cat.byPopularity().stream().filter(b -> !known.contains(b)).limit(k).toList();
    }
}
