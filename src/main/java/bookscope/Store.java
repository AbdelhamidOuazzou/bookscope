package bookscope;

import static bookscope.Util.LIKE;
import static bookscope.Util.toInt;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/**
 * Access to the BookScope data server (a standalone mongod or a mongos router).
 *
 * <p>Data model (database "bookscope"):
 * <pre>
 *   books       {_id: book_id, goodreads_book_id, title, authors[], year, tags: [{t: tag_id, n: count}], ...}
 *   tags        {_id: tag_id, name, df (books carrying it), total, status_like}
 *   ratings     {_id: "user_id:book_id", user_id, book_id, rating, seq}      sharded by user_id
 *   to_read     {_id: "user_id:book_id", user_id, book_id, seq}              sharded by user_id
 *   user_stats  {_id: user_id, n, sum}                                       sharded by _id (= user_id)
 *   book_stats  {_id: book_id, n, sum, n_pos}           precomputed, maintained by F6
 *   meta        {_id: "seq" | "load", ...}
 *   rating_changes  audit of ratings replaced by F6
 * </pre>
 * Catalogue collections (books, tags, book_stats) are small and are read once per process into
 * memory (Catalog). Reader activity is always queried from the server.
 */
public final class Store implements AutoCloseable {
    public static final String DEFAULT_URI = System.getenv().getOrDefault("BOOKSCOPE_URI", "mongodb://localhost:27100");
    public static final String DB_NAME = System.getenv().getOrDefault("BOOKSCOPE_DB", "bookscope");

    public record Rating(int bookId, int rating, int seq) {
    }

    public record CoLiked(List<Integer> likers, Map<Integer, Integer> co) {
    }

    public final String uri;
    public final MongoClient client;
    public final MongoDatabase db;
    private Catalog catalog;

    public Store(String uri) {
        this(uri, DB_NAME);
    }

    public Store(String uri, String dbName) {
        this.uri = uri;
        this.client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(5, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS))
                .build());
        this.db = client.getDatabase(dbName);
    }

    public Catalog catalog() {
        if (catalog == null) {
            if (db.getCollection("books").estimatedDocumentCount() == 0) {
                throw new BookScopeException("no data loaded at " + uri + "; run the load command first");
            }
            catalog = new Catalog(db);
        }
        return catalog;
    }

    public void refresh() {
        catalog = null;
    }

    /** A reader's ratings in time order, optionally truncated to the k earliest. */
    public List<Rating> history(int userId, Integer truncate) {
        FindIterable<Document> cursor = db.getCollection("ratings").find(eq("user_id", userId))
                .projection(new Document("_id", 0).append("book_id", 1).append("rating", 1).append("seq", 1))
                .sort(new Document("seq", 1));
        if (truncate != null && truncate != 0) {
            cursor = cursor.limit(truncate);
        }
        List<Rating> out = new ArrayList<>();
        for (Document d : cursor) {
            out.add(new Rating(toInt(d.get("book_id")), toInt(d.get("rating")), toInt(d.get("seq"))));
        }
        return out;
    }

    public List<Integer> likers(int bookId) {
        List<Integer> out = new ArrayList<>();
        for (Document d : db.getCollection("ratings").find(and(eq("book_id", bookId), gte("rating", LIKE)))
                .projection(new Document("_id", 0).append("user_id", 1))) {
            out.add(toInt(d.get("user_id")));
        }
        return out;
    }

    /** For every other book, how many readers who liked the book also liked it. */
    public CoLiked coLiked(int bookId) {
        List<Integer> likers = likers(bookId);
        Map<Integer, Integer> co = new HashMap<>();
        if (likers.isEmpty()) {
            return new CoLiked(likers, co);
        }
        List<Document> pipeline = List.of(
                new Document("$match", new Document("user_id", new Document("$in", likers)).append("rating", new Document("$gte", LIKE))),
                new Document("$group", new Document("_id", "$book_id").append("co", new Document("$sum", 1))));
        for (Document d : db.getCollection("ratings").aggregate(pipeline).allowDiskUse(true)) {
            co.put(toInt(d.get("_id")), toInt(d.get("co")));
        }
        co.remove(bookId);
        return new CoLiked(likers, co);
    }

    public Map<Integer, Integer> activity(Iterable<Integer> userIds) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Document d : db.getCollection("user_stats").find(in("_id", userIds)).projection(new Document("n", 1))) {
            out.put(toInt(d.get("_id")), toInt(d.get("n")));
        }
        return out;
    }

    @Override
    public void close() {
        client.close();
    }
}
