package bookscope.tools;

import bookscope.Util;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Apply the selection rule to Goodbooks-10k and write the prepared JSON Lines files.
 *
 * <p>Steps (all deterministic, so the preparation is reproducible):
 * <ol>
 *   <li>keep the readers selected by the rule (default: user_id from 30000 to 39999);</li>
 *   <li>before anything is loaded, set aside two disjoint sets of real ratings:
 *       eval_set.jsonl, the 3 most recent ratings of ~20% of the selected readers (E3), and
 *       update_batch.jsonl, the 2 most recent ratings of ~5% of other selected readers (F6).
 *       "Most recent" uses the row order of ratings.csv, which the documentation says is time order;</li>
 *   <li>write books (with their tags), tags, ratings, to_read and a report (sizes, distributions,
 *       data-quality issues with concrete identifiers).</li>
 * </ol>
 */
public final class PrepareData {
    /** Tag names that describe a reading status, format or ownership rather than the book's content. */
    private static final String[] STATUS_TAG_PREFIXES = {"to-read", "currently-reading", "read-in-", "read-20", "read-19", "owned",
        "own-", "i-own", "books-i-own", "my-books", "my-library", "library", "kindle",
        "ebook", "e-book", "audiobook", "audio-book", "wish", "tbr", "dnf", "default",
        "favorite", "favourite", "fave", "to-buy", "borrowed", "re-read", "read-more-than"};
    private static final Set<String> STATUS_TAG_NAMES = Set.of("read", "re-read", "reread", "abandoned", "did-not-finish", "have", "books",
            "book-club", "bookclub", "on-hold", "unfinished", "own", "my-favorites");
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private record Row(int seq, int book, int rating) {
    }

    private PrepareData() {
    }

    /** Stable pseudo-random bucket in [0, 100), independent of the selection rule. */
    static int bucket(int userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(("bookscope:" + userId).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).mod(BigInteger.valueOf(100)).intValue();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean isStatusTag(String name) {
        return STATUS_TAG_NAMES.contains(name) || Arrays.stream(STATUS_TAG_PREFIXES).anyMatch(name::startsWith);
    }

    private static Document quantiles(Collection<Integer> counts) {
        int[] values = counts.stream().mapToInt(i -> i).sorted().toArray();
        if (values.length == 0) {
            return new Document();
        }
        Document out = new Document("min", values[0]);
        for (Map.Entry<String, Double> q : Map.of("p25", 0.25, "p75", 0.75, "p95", 0.95, "p99", 0.99).entrySet()) {
            out.append(q.getKey(), values[Math.min(values.length - 1, (int) (q.getValue() * values.length))]);
        }
        double sum = 0;
        for (int v : values) {
            sum += v;
        }
        return out.append("median", Util.median(counts)).append("max", values[values.length - 1])
                .append("mean", Util.rnd(sum / values.length, 2));
    }

    private static int writeJsonl(Path path, Collection<Document> docs) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Document doc : docs) {
                out.write(doc.toJson(JSON));
                out.write('\n');
            }
        }
        return docs.size();
    }

    private static Integer toInt(String value) {
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static String orNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void issue(Document issues, String key, String description, List<?> examples) {
        issues.append(key, new Document("description", description).append("count", examples.size())
                .append("examples", new ArrayList<>(examples.subList(0, Math.min(5, examples.size())))));
    }

    @SuppressWarnings("unchecked")
    public static void run(Args args) throws IOException {
        Path raw = Path.of(args.get("--raw", "data/raw"));
        Path output = Path.of(args.get("--output", "data/prepared"));
        int[] range = args.getPair("--readers", 30000, 39999);
        boolean allReaders = args.has("--all-readers");
        int evalShare = args.getInt("--eval-share", 20);
        int evalLast = args.getInt("--eval-last", 3);
        int updateShare = args.getInt("--update-share", 5);
        int updateLast = args.getInt("--update-last", 2);
        Files.createDirectories(output);
        java.util.function.IntPredicate selected = u -> allReaders || (range[0] <= u && u <= range[1]);
        Document issues = new Document();

        // ---- catalogue -------------------------------------------------------------------
        Map<Integer, Document> books = new LinkedHashMap<>();
        Map<Integer, Integer> byGoodreads = new HashMap<>();
        Map<String, List<Integer>> missing = new LinkedHashMap<>();
        Csv.maps(raw.resolve("books.csv"), row -> {
            int bookId = Integer.parseInt(row.get("book_id"));
            Document doc = new Document("_id", bookId)
                    .append("goodreads_book_id", Integer.valueOf(row.get("goodreads_book_id")))
                    .append("work_id", Integer.valueOf(row.get("work_id")))
                    .append("title", row.get("title"))
                    .append("original_title", orNull(row.get("original_title")))
                    .append("authors", Arrays.stream(row.get("authors").split(",")).map(String::strip).filter(a -> !a.isEmpty()).toList())
                    .append("year", toInt(row.get("original_publication_year")))
                    .append("language", orNull(row.get("language_code")))
                    .append("isbn", orNull(row.get("isbn")))
                    .append("gr_ratings_count", Integer.valueOf(row.get("ratings_count")))
                    .append("gr_average_rating", Double.valueOf(row.get("average_rating")))
                    .append("tags", new ArrayList<Document>());
            for (String field : List.of("original_title", "year", "language", "isbn")) {
                if (doc.get(field) == null) {
                    missing.computeIfAbsent(field, x -> new ArrayList<>()).add(bookId);
                }
            }
            books.put(bookId, doc);
            byGoodreads.put(doc.getInteger("goodreads_book_id"), bookId);
        });
        missing.forEach((field, ids) -> issue(issues, "missing_" + field, "books.csv rows with an empty " + field, ids));

        Map<String, List<Integer>> titles = new TreeMap<>();
        books.values().forEach(doc -> titles.computeIfAbsent(doc.getString("title").strip().toLowerCase(Locale.ROOT), x -> new ArrayList<>())
                .add(doc.getInteger("_id")));
        List<Document> ambiguous = new ArrayList<>();
        titles.forEach((title, ids) -> {
            if (ids.size() > 1) {
                ambiguous.add(new Document("title", title).append("book_ids", ids));
            }
        });
        issue(issues, "ambiguous_titles", "titles shared by several book_id values (title is not an identifier)", ambiguous);

        Map<Integer, String> tagNames = new HashMap<>();
        Csv.maps(raw.resolve("tags.csv"), row -> tagNames.put(Integer.valueOf(row.get("tag_id")), row.get("tag_name")));

        Map<Long, Document> seenPairs = new HashMap<>();
        List<Document> duplicates = new ArrayList<>();
        List<Document> unresolved = new ArrayList<>();
        List<Document> nonPositive = new ArrayList<>();
        List<Document> unknownTags = new ArrayList<>();
        int[] lineNo = {1};
        Csv.rows(raw.resolve("book_tags.csv"), fields -> {
            lineNo[0]++;
            int grId = Integer.parseInt(fields[0]);
            int tagId = Integer.parseInt(fields[1]);
            int count = Integer.parseInt(fields[2]);
            Integer bookId = byGoodreads.get(grId);
            if (bookId == null) {
                unresolved.add(new Document("line", lineNo[0]).append("goodreads_book_id", grId));
                return;
            }
            if (!tagNames.containsKey(tagId)) {
                unknownTags.add(new Document("line", lineNo[0]).append("tag_id", tagId));
                return;
            }
            if (count <= 0) {
                nonPositive.add(new Document("line", lineNo[0]).append("book_id", bookId).append("tag_id", tagId).append("count", count));
                return;
            }
            long key = bookId * 1_000_000L + tagId;
            Document seen = seenPairs.get(key);
            if (seen != null) {
                duplicates.add(new Document("line", lineNo[0]).append("book_id", bookId).append("goodreads_book_id", grId)
                        .append("tag_id", tagId).append("tag", tagNames.get(tagId)).append("count", count)
                        .append("first_count", seen.getInteger("n")));
                // Keep the larger count: duplicated rows describe the same (book, tag) aggregate.
                seen.put("n", Math.max(seen.getInteger("n"), count));
                return;
            }
            Document entry = new Document("t", tagId).append("n", count);
            seenPairs.put(key, entry);
            ((List<Document>) books.get(bookId).get("tags")).add(entry);
        });
        issue(issues, "book_tags_duplicates", "book_tags.csv rows repeating a (goodreads_book_id, tag_id) pair", duplicates);
        issue(issues, "book_tags_non_positive", "book_tags.csv rows with a count <= 0", nonPositive);
        issue(issues, "book_tags_unresolved", "book_tags.csv rows whose goodreads_book_id is not in books.csv", unresolved);
        issue(issues, "book_tags_unknown_tag", "book_tags.csv rows whose tag_id is not in tags.csv", unknownTags);

        Map<Integer, Integer> tagDf = new TreeMap<>();
        Map<Integer, Long> tagTotal = new HashMap<>();
        for (Document doc : books.values()) {
            List<Document> entries = (List<Document>) doc.get("tags");
            entries.sort(Comparator.comparingInt((Document e) -> -e.getInteger("n")).thenComparingInt(e -> e.getInteger("t")));
            for (Document e : entries) {
                tagDf.merge(e.getInteger("t"), 1, Integer::sum);
                tagTotal.merge(e.getInteger("t"), (long) e.getInteger("n"), Long::sum);
            }
        }
        List<Document> tags = new ArrayList<>();
        tagDf.forEach((t, df) -> tags.add(new Document("_id", t).append("name", tagNames.get(t)).append("df", df)
                .append("total", tagTotal.get(t)).append("status_like", isStatusTag(tagNames.get(t)))));
        List<Document> status = new ArrayList<>();
        tags.stream().filter(t -> t.getBoolean("status_like"))
                .sorted(Comparator.comparingInt((Document t) -> -t.getInteger("df")))
                .forEach(t -> status.add(new Document("tag_id", t.get("_id")).append("name", t.get("name")).append("books", t.get("df"))));
        issue(issues, "status_tags", "tags describing reading status/ownership rather than content (by number of books)", status);

        // ---- reader activity -----------------------------------------------------------------
        Map<Integer, List<Row>> history = new TreeMap<>();
        Set<Long> pairs = new HashSet<>();
        List<Document> dupRatings = new ArrayList<>();
        List<Document> unknownBooks = new ArrayList<>();
        int[] seq = {0};
        Csv.rows(raw.resolve("ratings.csv"), fields -> {
            seq[0]++;
            int user = Integer.parseInt(fields[0]);
            if (!selected.test(user)) {
                return;
            }
            int book = Integer.parseInt(fields[1]);
            if (!books.containsKey(book)) {
                unknownBooks.add(new Document("row", seq[0]).append("user_id", user).append("book_id", book));
                return;
            }
            if (!pairs.add(user * 1_000_000L + book)) {
                dupRatings.add(new Document("row", seq[0]).append("user_id", user).append("book_id", book));
                return;
            }
            history.computeIfAbsent(user, x -> new ArrayList<>()).add(new Row(seq[0], book, Integer.parseInt(fields[2])));
        });
        issue(issues, "ratings_duplicate_pairs", "ratings.csv rows repeating a (user_id, book_id) pair (selected readers)", dupRatings);
        issue(issues, "ratings_unknown_book", "ratings.csv rows whose book_id is not in books.csv (selected readers)", unknownBooks);

        List<Document> ratings = new ArrayList<>();
        List<Document> evalSet = new ArrayList<>();
        List<Document> updateBatch = new ArrayList<>();
        Map<Integer, Integer> perReader = new HashMap<>();
        Map<Integer, Integer> perBook = new HashMap<>();
        Map<Integer, Integer> ratingValues = new TreeMap<>();
        history.forEach((user, rows) -> { // rows are already in time (row) order
            int b = bucket(user);
            int cut = 0;
            List<Document> target = null;
            if (b < evalShare) {
                cut = evalLast;
                target = evalSet;
            } else if (b < evalShare + updateShare) {
                cut = updateLast;
                target = updateBatch;
            }
            int kept = rows.size() - cut;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                Document doc = new Document("user_id", user).append("book_id", r.book()).append("rating", r.rating()).append("seq", r.seq());
                if (i < kept) {
                    ratings.add(doc);
                    perReader.merge(user, 1, Integer::sum);
                    perBook.merge(r.book(), 1, Integer::sum);
                    ratingValues.merge(r.rating(), 1, Integer::sum);
                } else {
                    target.add(doc);
                }
            }
        });

        List<Document> toRead = new ArrayList<>();
        List<Document> toReadRated = new ArrayList<>();
        List<Document> toReadDup = new ArrayList<>();
        Set<Long> seenToRead = new HashSet<>();
        int[] readSeq = {0};
        Csv.rows(raw.resolve("to_read.csv"), fields -> {
            readSeq[0]++;
            int user = Integer.parseInt(fields[0]);
            int book = Integer.parseInt(fields[1]);
            if (!selected.test(user) || !books.containsKey(book)) {
                return;
            }
            long key = user * 1_000_000L + book;
            if (!seenToRead.add(key)) {
                toReadDup.add(new Document("row", readSeq[0]).append("user_id", user).append("book_id", book));
                return;
            }
            if (pairs.contains(key)) {
                toReadRated.add(new Document("row", readSeq[0]).append("user_id", user).append("book_id", book));
            }
            toRead.add(new Document("user_id", user).append("book_id", book).append("seq", readSeq[0]));
        });
        issue(issues, "to_read_also_rated", "to_read.csv entries for a book the same reader also rated", toReadRated);
        issue(issues, "to_read_duplicates", "to_read.csv rows repeating a (user_id, book_id) pair", toReadDup);

        List<Integer> unrated = books.keySet().stream().filter(b -> !perBook.containsKey(b)).sorted().toList();
        issue(issues, "books_unrated_in_selection", "catalogue books with no rating among the selected readers", unrated);

        long bookTagLinks = books.values().stream().mapToLong(b -> ((List<Document>) b.get("tags")).size()).sum();
        Document counts = new Document("readers", perReader.size()).append("books_in_catalogue", books.size())
                .append("books_rated", perBook.size()).append("ratings", ratings.size()).append("eval_set", evalSet.size())
                .append("update_batch", updateBatch.size()).append("to_read", toRead.size()).append("tags", tags.size())
                .append("book_tag_links", bookTagLinks);
        Document values = new Document();
        ratingValues.forEach((v, n) -> values.append(String.valueOf(v), n));
        String rule = allReaders ? "all readers" : "user_id from " + range[0] + " to " + range[1];
        Document report = new Document("selection_rule", rule)
                .append("holdout", new Document("eval_set", evalLast + " most recent ratings of readers with bucket < " + evalShare)
                        .append("update_batch", updateLast + " most recent ratings of readers with " + evalShare + " <= bucket < " + (evalShare + updateShare))
                        .append("bucket", "int(sha1('bookscope:<user_id>'), 16) % 100"))
                .append("counts", counts)
                .append("ratings_per_reader", quantiles(perReader.values()))
                .append("ratings_per_book", quantiles(perBook.values()))
                .append("rating_values", values)
                .append("issues", issues);

        List<Document> sortedBooks = new ArrayList<>(books.values());
        sortedBooks.sort(Comparator.comparingInt(b -> b.getInteger("_id")));
        writeJsonl(output.resolve("books.jsonl"), sortedBooks);
        writeJsonl(output.resolve("tags.jsonl"), tags);
        writeJsonl(output.resolve("ratings.jsonl"), ratings);
        writeJsonl(output.resolve("to_read.jsonl"), toRead);
        writeJsonl(output.resolve("eval_set.jsonl"), evalSet);
        writeJsonl(output.resolve("update_batch.jsonl"), updateBatch);
        Files.writeString(output.resolve("report.json"),
                report.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()), StandardCharsets.UTF_8);

        System.out.println("Selection: " + rule);
        counts.forEach((key, value) -> System.out.printf("  %-20s %,10d%n", key, ((Number) value).longValue()));
        System.out.println("Data issues:");
        issues.forEach((key, value) -> System.out.printf("  %-28s %,8d%n", key, ((Document) value).getInteger("count")));
        System.out.println("Prepared files in " + output.toAbsolutePath());
    }
}
