package bookscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.MongoException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests on a small database built from real prepared records.
 *
 * <p>Needs a running mongod (default mongodb://localhost:27100, override with $BOOKSCOPE_TEST_URI)
 * and data/prepared/ from the prepare command. Run: ./mvnw test
 */
class ServicesTest {
    private static final Path PREPARED = Path.of("data", "prepared");
    private static final String URI = System.getenv().getOrDefault("BOOKSCOPE_TEST_URI", "mongodb://localhost:27100");
    private static final String DB = "bookscope_test";

    private static Store store;
    private static int user;
    private static int rated;
    private static int unrated;

    private static List<Document> jsonl(String name, int limit) throws IOException {
        List<Document> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(PREPARED.resolve(name), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null && out.size() < limit) {
                out.add(Document.parse(line));
            }
        }
        return out;
    }

    @BeforeAll
    static void setUp() throws IOException {
        assumeTrue(Files.exists(PREPARED.resolve("ratings.jsonl")), "run the prepare command first");
        store = new Store(URI, DB);
        try {
            store.client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (MongoException e) {
            assumeTrue(false, "no mongod at " + URI);
        }
        store.db.drop();
        store.db.getCollection("books").insertMany(jsonl("books.jsonl", Integer.MAX_VALUE));
        store.db.getCollection("tags").insertMany(jsonl("tags.jsonl", Integer.MAX_VALUE));
        List<Document> ratings = jsonl("ratings.jsonl", 20000);
        for (Document r : ratings) {
            r.put("_id", r.get("user_id") + ":" + r.get("book_id"));
        }
        store.db.getCollection("ratings").insertMany(ratings);
        store.db.getCollection("ratings").aggregate(List.of(
                new Document("$group", new Document("_id", "$book_id").append("n", new Document("$sum", 1)).append("sum", new Document("$sum", "$rating"))
                        .append("n_pos", new Document("$sum", new Document("$cond", List.of(new Document("$gte", List.of("$rating", 4)), 1, 0))))),
                new Document("$merge", new Document("into", "book_stats")))).toCollection();
        store.db.getCollection("ratings").aggregate(List.of(
                new Document("$group", new Document("_id", "$user_id").append("n", new Document("$sum", 1)).append("sum", new Document("$sum", "$rating"))),
                new Document("$merge", new Document("into", "user_stats")))).toCollection();
        int maxSeq = ratings.stream().mapToInt(r -> r.getInteger("seq")).max().orElseThrow();
        store.db.getCollection("meta").insertOne(new Document("_id", "seq").append("value", maxSeq));
        user = ratings.get(0).getInteger("user_id");
        rated = ratings.get(0).getInteger("book_id");
        Set<Integer> ratedByUser = new HashSet<>();
        ratings.stream().filter(r -> r.getInteger("user_id") == user).forEach(r -> ratedByUser.add(r.getInteger("book_id")));
        unrated = store.catalog().books.keySet().stream().filter(b -> !ratedByUser.contains(b)).findFirst().orElseThrow();
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.db.drop();
            store.close();
        }
    }

    private static String batch(Path dir, List<Document> rows) throws IOException {
        Path file = Files.createTempFile(dir, "batch", ".jsonl");
        List<String> lines = rows.stream().map(Document::toJson).toList();
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file.toString();
    }

    private static Document row(int bookId, int rating) {
        return new Document("user_id", user).append("book_id", bookId).append("rating", rating);
    }

    @Test
    void updateIsIdempotentAndLatestScoreWins(@TempDir Path dir) throws Exception {
        int old = store.db.getCollection("ratings").find(new Document("_id", user + ":" + rated)).first().getInteger("rating");
        int updated = old != 1 ? 1 : 2;
        String file = batch(dir, List.of(row(rated, updated), row(unrated, 4)));
        Document first = Update.update(store, file);
        assertEquals(1, first.getInteger("inserted"));
        assertEquals(1, first.getInteger("replaced_different_score"));
        long before = store.db.getCollection("ratings").countDocuments();
        Document second = Update.update(store, file);
        assertEquals(0, second.getInteger("inserted"));
        assertEquals(0, second.getInteger("replaced_different_score"));
        assertEquals(2, second.getInteger("unchanged_already_stored"));
        assertEquals(before, store.db.getCollection("ratings").countDocuments());
        assertEquals(1, store.db.getCollection("rating_changes").countDocuments(new Document("old_rating", old).append("new_rating", updated)));
    }

    @Test
    void updateRejectsInvalidRows(@TempDir Path dir) throws Exception {
        Document result = Update.update(store, batch(dir, List.of(row(123456, 4), row(rated, 9))));
        assertEquals(2, result.getInteger("rejected_total"));
        assertEquals(0, result.getInteger("inserted"));
    }

    @Test
    void ambiguousTitleIsShown() {
        Document result = Explore.explore(store, "'Salem's Lot", null, 10, 5);
        assertEquals("ambiguous", result.getString("status"));
        assertTrue(result.getList("matches", Document.class).stream().anyMatch(m -> m.getInteger("book_id") == 349));
    }

    @Test
    void compareReportsInsufficientOverlap() {
        Document result = Compare.compare(store, user, user, 2, 2, 10);
        assertEquals("insufficient_overlap", result.getString("status"));
    }

    @Test
    void normaliseTitle() {
        assertEquals("salem s lot", Catalog.normaliseTitle("  'Salem's   LOT "));
    }
}
