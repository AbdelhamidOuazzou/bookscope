package bookscope;

import static bookscope.Util.cmp;
import static bookscope.Util.toInt;

import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;

/** In-memory view of books, tags and per-book statistics. */
public final class Catalog {
    /** A tag carried by >= 50% of the books is too common to link two books. */
    public static final double GENERIC_DF_SHARE = 0.5;
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);

    public record TagCount(int tag, int count) {
    }

    public record Book(int id, int goodreadsId, String title, String originalTitle, String language,
                       List<String> authors, Integer year, List<TagCount> tags) {
    }

    public record Tag(int id, String name, int df, boolean statusLike, double idf, boolean generic) {
    }

    public record Stats(int n, long sum, int nPos) {
    }

    public final Map<Integer, Book> books = new LinkedHashMap<>();
    public final Map<Integer, Tag> tags = new LinkedHashMap<>();
    public final Map<Integer, Stats> stats = new LinkedHashMap<>();
    public final int nBooks;
    public final long nReaders;

    private List<Integer> byPopularity;
    private Set<Integer> top100;
    private Map<Integer, Map<Integer, Double>> tagVectors;
    private Map<Integer, List<Map.Entry<Integer, Double>>> tagIndex;

    public Catalog(MongoDatabase db) {
        for (Document d : db.getCollection("books").find()) {
            List<TagCount> bookTags = new ArrayList<>();
            for (Document e : d.getList("tags", Document.class)) {
                bookTags.add(new TagCount(toInt(e.get("t")), toInt(e.get("n"))));
            }
            int id = toInt(d.get("_id"));
            books.put(id, new Book(id, toInt(d.get("goodreads_book_id")), d.getString("title"), d.getString("original_title"),
                    d.getString("language"), d.getList("authors", String.class),
                    d.get("year") == null ? null : toInt(d.get("year")), bookTags));
        }
        nBooks = books.size();
        for (Document d : db.getCollection("tags").find()) {
            int id = toInt(d.get("_id"));
            int df = toInt(d.get("df"));
            boolean status = d.getBoolean("status_like");
            tags.put(id, new Tag(id, d.getString("name"), df, status, Math.log((double) nBooks / df),
                    status || df >= GENERIC_DF_SHARE * nBooks));
        }
        for (Document d : db.getCollection("book_stats").find()) {
            stats.put(toInt(d.get("_id")), new Stats(toInt(d.get("n")), ((Number) d.get("sum")).longValue(), toInt(d.get("n_pos"))));
        }
        nReaders = db.getCollection("user_stats").countDocuments();
    }

    public static String normaliseTitle(String text) {
        String cleaned = PUNCTUATION.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return cleaned.isEmpty() ? "" : String.join(" ", cleaned.split("\\s+"));
    }

    public String title(int bookId) {
        return books.get(bookId).title();
    }

    /** Number of ratings of the book in the stored selection. */
    public int n(int bookId) {
        Stats s = stats.get(bookId);
        return s == null ? 0 : s.n();
    }

    public int nPos(int bookId) {
        Stats s = stats.get(bookId);
        return s == null ? 0 : s.nPos();
    }

    /** Inverse popularity weight: books rated by many readers say little about a reader. */
    public double bookWeight(int bookId) {
        return Math.log((nReaders + 1.0) / (n(bookId) + 1.0));
    }

    public List<Integer> byPopularity() {
        if (byPopularity == null) {
            byPopularity = new ArrayList<>(stats.keySet());
            byPopularity.sort(Comparator.<Integer>comparingInt(b -> -stats.get(b).n()).thenComparingInt(b -> b));
        }
        return byPopularity;
    }

    public Set<Integer> top100() {
        if (top100 == null) {
            List<Integer> ranked = byPopularity();
            top100 = new HashSet<>(ranked.subList(0, Math.min(100, ranked.size())));
        }
        return top100;
    }

    /** The most rated books, used as hubs by F4 and F5. */
    public Set<Integer> hubs(double share) {
        List<Integer> ranked = byPopularity();
        return new HashSet<>(ranked.subList(0, Math.min(Math.max(1, (int) (share * nBooks)), ranked.size())));
    }

    /** book_id -> {tag_id: weight} over specific (non-generic) tags, L2-normalised. */
    public Map<Integer, Map<Integer, Double>> tagVectors() {
        if (tagVectors == null) {
            tagVectors = new LinkedHashMap<>();
            for (Book book : books.values()) {
                Map<Integer, Double> vec = new LinkedHashMap<>();
                double squares = 0;
                for (TagCount e : book.tags()) {
                    Tag tag = tags.get(e.tag());
                    if (!tag.generic()) {
                        double w = Math.log1p(e.count()) * tag.idf();
                        vec.put(e.tag(), w);
                        squares += w * w;
                    }
                }
                double norm = squares > 0 ? Math.sqrt(squares) : 1.0;
                vec.replaceAll((t, w) -> w / norm);
                tagVectors.put(book.id(), vec);
            }
        }
        return tagVectors;
    }

    /** tag_id -> [(book_id, weight)] for specific tags. */
    private Map<Integer, List<Map.Entry<Integer, Double>>> tagIndex() {
        if (tagIndex == null) {
            tagIndex = new HashMap<>();
            tagVectors().forEach((bookId, vec) -> vec.forEach((t, w) ->
                    tagIndex.computeIfAbsent(t, x -> new ArrayList<>()).add(Map.entry(bookId, w))));
        }
        return tagIndex;
    }

    /** Cosine between a tag profile {tag: weight} and every book sharing a specific tag. */
    public Map<Integer, Double> tagSimilar(Map<Integer, Double> profile, Set<Integer> exclude) {
        Map<Integer, Double> scores = new HashMap<>();
        profile.forEach((t, w) -> {
            for (Map.Entry<Integer, Double> e : tagIndex().getOrDefault(t, List.of())) {
                scores.merge(e.getKey(), w * e.getValue(), Double::sum);
            }
        });
        scores.keySet().removeAll(exclude);
        return scores;
    }

    /** Tags carried by both books, specific tags first, with the counts on each book. 0 = no limit. */
    public List<Document> sharedTags(int a, int b, int limit) {
        Map<Integer, Integer> tb = new HashMap<>();
        for (TagCount e : books.get(b).tags()) {
            tb.put(e.tag(), e.count());
        }
        record Shared(boolean generic, double score, int id, Document doc) {
        }
        List<Shared> shared = new ArrayList<>();
        for (TagCount e : books.get(a).tags()) {
            Integer countB = tb.get(e.tag());
            if (countB != null) {
                Tag tag = tags.get(e.tag());
                shared.add(new Shared(tag.generic(), -Math.min(e.count(), countB) * tag.idf(), tag.id(),
                        new Document("tag", tag.name()).append("tag_id", tag.id()).append("count_a", e.count())
                                .append("count_b", countB).append("books_with_tag", tag.df()).append("generic", tag.generic())));
            }
        }
        shared.sort((x, y) -> {
            int c = Boolean.compare(x.generic(), y.generic());
            c = c != 0 ? c : cmp(x.score(), y.score());
            return c != 0 ? c : Integer.compare(x.id(), y.id());
        });
        List<Document> out = new ArrayList<>();
        for (Shared s : shared) {
            if (limit == 0 || out.size() < limit) {
                out.add(s.doc());
            }
        }
        return out;
    }

    /** Every book whose title or original title contains the query (exact matches first).
     *  Several matches are returned as they are: the caller must show the ambiguity. */
    public List<Book> findBooks(String query) {
        String q = normaliseTitle(query);
        record Found(int rank, int popularity, Book book) {
        }
        List<Found> found = new ArrayList<>();
        for (Book b : books.values()) {
            Set<String> names = new LinkedHashSet<>();
            names.add(normaliseTitle(b.title()));
            names.add(normaliseTitle(b.originalTitle() == null ? "" : b.originalTitle()));
            if (names.contains(q)) {
                found.add(new Found(0, -n(b.id()), b));
            } else if (!q.isEmpty() && names.stream().anyMatch(name -> name.contains(q))) {
                found.add(new Found(1, -n(b.id()), b));
            }
        }
        found.sort(Comparator.comparingInt(Found::rank).thenComparingInt(Found::popularity).thenComparingInt(f -> f.book().id()));
        return found.stream().map(Found::book).toList();
    }

    public List<Tag> findTags(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<Tag> exact = tags.values().stream().filter(t -> t.name().equals(q)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return tags.values().stream().filter(t -> t.name().contains(q))
                .sorted(Comparator.comparingInt((Tag t) -> -t.df()).thenComparingInt(Tag::id)).toList();
    }

    public Document bookSummary(int bookId) {
        Book b = books.get(bookId);
        return new Document("book_id", bookId).append("title", b.title()).append("authors", b.authors())
                .append("year", b.year()).append("ratings_in_selection", n(bookId));
    }
}
