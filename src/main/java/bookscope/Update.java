package bookscope;

import static bookscope.Util.LIKE;
import static bookscope.Util.toInt;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import bookscope.tools.Csv;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bson.Document;

/**
 * F6 - import a batch of reader activity.
 *
 * <p>Identity of a rating: the pair (user_id, book_id), stored as _id "user_id:book_id".
 * Conflict policy: the latest imported score wins. When a pair already exists with a different
 * score, the stored rating is replaced, it moves to the end of the reader's history (new seq),
 * and the previous value is kept in rating_changes. A pair with the same score is left untouched,
 * so importing the same batch twice changes nothing. Inside one batch, the last row of a pair wins.
 * book_stats and user_stats are updated incrementally in the same pass.
 */
public final class Update {
    private record Pair(int user, int book) {
        String key() {
            return user + ":" + book;
        }
    }

    private Update() {
    }

    private static List<Map<String, Object>> readBatch(Path path) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (path.toString().endsWith(".csv")) {
            rows.addAll(Csv.readAsMaps(path));
        } else {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    rows.add(Document.parse(line));
                }
            }
        }
        return rows;
    }

    /** Integer value of a batch field, or null when it is missing or not an integer. */
    private static Integer integer(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Document update(Store store, String file) throws IOException, NoSuchAlgorithmException {
        Catalog cat = store.catalog();
        Path path = Path.of(file);
        List<Map<String, Object>> rows = readBatch(path);
        String batchId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).substring(0, 16);

        List<Document> rejected = new ArrayList<>();
        Map<Pair, Integer> latest = new LinkedHashMap<>();
        int i = 0;
        for (Map<String, Object> row : rows) {
            i++;
            Integer user = integer(row.get("user_id"));
            Integer book = integer(row.get("book_id"));
            Integer rating = integer(row.get("rating"));
            if (user == null || book == null || rating == null) {
                rejected.add(new Document("row", i).append("reason", "missing or non-integer user_id/book_id/rating"));
            } else if (rating < 1 || rating > 5) {
                rejected.add(new Document("row", i).append("reason", "rating " + rating + " outside 1..5"));
            } else if (!cat.books.containsKey(book)) {
                rejected.add(new Document("row", i).append("reason", "unknown book_id " + book));
            } else {
                latest.put(new Pair(user, book), rating); // later rows of the same pair win
            }
        }
        int duplicatesInBatch = rows.size() - rejected.size() - latest.size();

        Map<String, Integer> existing = new HashMap<>();
        List<String> keys = latest.keySet().stream().map(Pair::key).toList();
        for (int start = 0; start < keys.size(); start += 5000) {
            for (Document d : store.db.getCollection("ratings")
                    .find(in("_id", keys.subList(start, Math.min(start + 5000, keys.size()))))
                    .projection(new Document("rating", 1))) {
                existing.put(d.getString("_id"), toInt(d.get("rating")));
            }
        }
        Set<Integer> batchReaders = new TreeSet<>();
        latest.keySet().forEach(p -> batchReaders.add(p.user()));
        Set<Integer> knownReaders = new HashSet<>();
        for (Document d : store.db.getCollection("user_stats").find(in("_id", batchReaders)).projection(new Document("_id", 1))) {
            knownReaders.add(toInt(d.get("_id")));
        }

        List<Map.Entry<Pair, Integer>> changes = new ArrayList<>();
        latest.forEach((pair, rating) -> {
            if (!rating.equals(existing.get(pair.key()))) {
                changes.add(Map.entry(pair, rating));
            }
        });
        long seqEnd = 0;
        if (!changes.isEmpty()) {
            seqEnd = ((Number) store.db.getCollection("meta").findOneAndUpdate(eq("_id", "seq"),
                    Updates.inc("value", changes.size()),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)).get("value")).longValue();
        }
        List<WriteModel<Document>> ops = new ArrayList<>();
        Map<Integer, int[]> bookInc = new LinkedHashMap<>(); // n, sum, n_pos
        Map<Integer, int[]> userInc = new LinkedHashMap<>(); // n, sum
        List<Document> audit = new ArrayList<>();
        int inserted = 0;
        int replaced = 0;
        int offset = 0;
        for (Map.Entry<Pair, Integer> change : changes) {
            Pair pair = change.getKey();
            int r = change.getValue();
            int seq = (int) (seqEnd - changes.size() + 1 + offset++);
            String key = pair.key();
            Integer old = existing.get(key);
            int[] bs = bookInc.computeIfAbsent(pair.book(), x -> new int[3]);
            int[] us = userInc.computeIfAbsent(pair.user(), x -> new int[2]);
            if (old == null) {
                ops.add(new InsertOneModel<>(new Document("_id", key).append("user_id", pair.user()).append("book_id", pair.book())
                        .append("rating", r).append("seq", seq)));
                bs[0]++;
                us[0]++;
                inserted++;
            } else {
                ops.add(new UpdateOneModel<>(and(eq("_id", key), eq("user_id", pair.user())),
                        Updates.combine(Updates.set("rating", r), Updates.set("seq", seq))));
                audit.add(new Document("_id", key + ":" + batchId).append("user_id", pair.user()).append("book_id", pair.book())
                        .append("old_rating", old).append("new_rating", r).append("batch", batchId));
                replaced++;
            }
            int previous = old == null ? 0 : old;
            bs[1] += r - previous;
            bs[2] += (r >= LIKE ? 1 : 0) - (old != null && old >= LIKE ? 1 : 0);
            us[1] += r - previous;
        }
        if (!ops.isEmpty()) {
            store.db.getCollection("ratings").bulkWrite(ops);
            UpdateOptions upsert = new UpdateOptions().upsert(true);
            List<WriteModel<Document>> bookOps = new ArrayList<>();
            bookInc.forEach((b, inc) -> bookOps.add(new UpdateOneModel<>(eq("_id", b),
                    new Document("$inc", new Document("n", inc[0]).append("sum", inc[1]).append("n_pos", inc[2])), upsert)));
            store.db.getCollection("book_stats").bulkWrite(bookOps);
            List<WriteModel<Document>> userOps = new ArrayList<>();
            userInc.forEach((u, inc) -> userOps.add(new UpdateOneModel<>(eq("_id", u),
                    new Document("$inc", new Document("n", inc[0]).append("sum", inc[1])), upsert)));
            store.db.getCollection("user_stats").bulkWrite(userOps);
        }
        if (!audit.isEmpty()) {
            store.db.getCollection("rating_changes").insertMany(audit);
        }
        store.refresh();
        batchReaders.removeAll(knownReaders);
        return new Document("service", "F6").append("status", rejected.isEmpty() ? "ok" : "ok_with_rejections")
                .append("batch", file).append("batch_id", batchId).append("rows", rows.size()).append("inserted", inserted)
                .append("replaced_different_score", replaced).append("unchanged_already_stored", latest.size() - changes.size())
                .append("duplicates_inside_batch", duplicatesInBatch)
                .append("new_readers", batchReaders.stream().limit(20).toList())
                .append("rejected", rejected.subList(0, Math.min(20, rejected.size()))).append("rejected_total", rejected.size())
                .append("policy", "identity = (user_id, book_id); latest imported score wins; previous score kept in rating_changes");
    }

    /** List the stored records behind a result (traceability). */
    public static Document records(Store store, List<Integer> users, List<Integer> books, boolean withTags) {
        Catalog cat = store.catalog();
        Document query = new Document();
        if (!users.isEmpty()) {
            query.append("user_id", new Document("$in", users));
        }
        if (!books.isEmpty()) {
            query.append("book_id", new Document("$in", books));
        }
        if (query.isEmpty()) {
            throw new BookScopeException("give at least one user or book");
        }
        List<Document> ratings = store.db.getCollection("ratings").find(query).projection(new Document("_id", 0))
                .sort(new Document("user_id", 1).append("seq", 1)).limit(500).into(new ArrayList<>());
        Document out = new Document("service", "records").append("status", "ok").append("ratings", ratings)
                .append("ratings_shown", ratings.size());
        if (withTags && !books.isEmpty()) {
            Document bookTags = new Document();
            for (int b : books) {
                if (cat.books.containsKey(b)) {
                    List<Document> tags = new ArrayList<>();
                    for (Catalog.TagCount e : cat.books.get(b).tags().stream().limit(20).toList()) {
                        tags.add(new Document("tag", cat.tags.get(e.tag()).name()).append("tag_id", e.tag()).append("count", e.count()));
                    }
                    bookTags.append(String.valueOf(b), tags);
                }
            }
            out.append("book_tags", bookTags);
        }
        return out;
    }
}
