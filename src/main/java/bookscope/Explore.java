package bookscope;

import static bookscope.Util.cmp;
import static bookscope.Util.rnd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;

/** F1 - explore a book and its connections. */
public final class Explore {
    public record Link(int sharedReaders, double cosine, double lift, boolean enough) {
    }

    public record ReaderLinks(List<Integer> likers, Map<Integer, Link> links) {
    }

    private static final Link NO_LINK = new Link(0, 0.0, 0.0, false);

    private Explore() {
    }

    /** Books co-liked with bookId: shared likers, cosine and lift over the stored readers. */
    public static ReaderLinks readerLinks(Store store, int bookId, int minShared) {
        Catalog cat = store.catalog();
        Store.CoLiked coLiked = store.coLiked(bookId);
        int likers = coLiked.likers().size();
        Map<Integer, Link> links = new HashMap<>();
        coLiked.co().forEach((other, shared) -> {
            int nPos = cat.nPos(other);
            links.put(other, new Link(shared,
                    nPos != 0 ? rnd(shared / Math.sqrt((double) likers * nPos)) : 0.0,
                    nPos != 0 ? rnd((double) shared * cat.nReaders / ((double) likers * nPos)) : 0.0,
                    shared >= minShared));
        });
        return new ReaderLinks(coLiked.likers(), links);
    }

    /** query or bookId must be given. */
    public static Document explore(Store store, String query, Integer bookId, int k, int minShared) {
        Catalog cat = store.catalog();
        if (bookId != null) {
            if (!cat.books.containsKey(bookId)) {
                throw new BookScopeException("unknown book_id " + bookId);
            }
        } else {
            List<Catalog.Book> matches = cat.findBooks(query);
            if (matches.isEmpty()) {
                return new Document("service", "F1").append("status", "not_found").append("query", query);
            }
            if (matches.size() > 1) {
                List<Document> shown = new ArrayList<>();
                for (Catalog.Book b : matches.subList(0, Math.min(25, matches.size()))) {
                    shown.add(cat.bookSummary(b.id()));
                }
                return new Document("service", "F1").append("status", "ambiguous").append("query", query)
                        .append("message", matches.size() + " books match this title; choose one with --id")
                        .append("matches", shown).append("total_matches", matches.size());
            }
            bookId = matches.get(0).id();
        }
        int id = bookId;
        Catalog.Book book = cat.books.get(id);
        ReaderLinks readerLinks = readerLinks(store, id, minShared);
        Map<Integer, Link> links = readerLinks.links();

        List<Integer> byReaders = new ArrayList<>();
        links.forEach((b, l) -> {
            if (l.enough()) {
                byReaders.add(b);
            }
        });
        byReaders.sort((x, y) -> {
            int c = cmp(links.get(y).cosine(), links.get(x).cosine());
            return c != 0 ? c : Integer.compare(x, y);
        });
        Map<Integer, Double> tagScores = cat.tagSimilar(cat.tagVectors().get(id), Set.of(id));
        List<Integer> byTags = new ArrayList<>();
        tagScores.forEach((b, s) -> {
            if (s > 0) {
                byTags.add(b);
            }
        });
        byTags.sort((x, y) -> {
            int c = cmp(tagScores.get(y), tagScores.get(x));
            return c != 0 ? c : Integer.compare(x, y);
        });

        List<Catalog.Tag> genericTags = new ArrayList<>();
        Set<Integer> genericIds = new HashSet<>();
        for (Catalog.TagCount e : book.tags()) {
            Catalog.Tag tag = cat.tags.get(e.tag());
            if (tag.generic()) {
                genericTags.add(tag);
                genericIds.add(tag.id());
            }
        }
        int genericOnly = 0;
        for (Catalog.Book other : cat.books.values()) {
            if (other.id() != id && !tagScores.containsKey(other.id())
                    && other.tags().stream().anyMatch(e -> genericIds.contains(e.tag()))) {
                genericOnly++;
            }
        }
        List<Document> topTags = new ArrayList<>();
        for (Catalog.TagCount e : book.tags().subList(0, Math.min(8, book.tags().size()))) {
            Catalog.Tag tag = cat.tags.get(e.tag());
            topTags.add(new Document("tag", tag.name()).append("count", e.count()).append("generic", tag.generic()));
        }
        Catalog.Stats stats = cat.stats.get(id);
        Document summary = cat.bookSummary(id).append("goodreads_book_id", book.goodreadsId())
                .append("original_title", book.originalTitle()).append("language", book.language())
                .append("readers_who_liked", readerLinks.likers().size())
                .append("average_rating_in_selection", stats == null ? null : rnd((double) stats.sum() / stats.n(), 3))
                .append("top_tags", topTags);
        return new Document("service", "F1").append("status", "ok").append("book", summary)
                .append("related_by_readers", entries(cat, id, byReaders, k, links, tagScores))
                .append("related_by_tags", entries(cat, id, byTags, k, links, tagScores))
                .append("generic_tag_note", new Document("generic_tags_on_book",
                        genericTags.stream().limit(5).map(Catalog.Tag::name).toList())
                        .append("books_linked_only_by_generic_tags", genericOnly)
                        .append("explanation", "tags carried by >= 50% of the books or describing reading status "
                                + "(e.g. to-read, favorites, owned) link almost every book and are ignored as evidence"))
                .append("parameters", new Document("k", k).append("min_shared_readers", minShared).append("like_threshold", 4));
    }

    private static List<Document> entries(Catalog cat, int id, List<Integer> books, int k, Map<Integer, Link> links,
                                          Map<Integer, Double> tagScores) {
        List<Document> out = new ArrayList<>();
        for (int other : books.subList(0, Math.min(k, books.size()))) {
            Link link = links.getOrDefault(other, NO_LINK);
            double tagScore = rnd(tagScores.getOrDefault(other, 0.0));
            List<Document> shared = cat.sharedTags(id, other, 5);
            String basis;
            if (link.enough() && tagScore > 0) {
                basis = "shared readership and specific tags";
            } else if (link.enough()) {
                basis = "shared readership";
            } else if (tagScore > 0) {
                basis = "specific tags only (few shared readers)";
            } else {
                basis = "generic tags only";
            }
            out.add(cat.bookSummary(other).append("basis", basis).append("shared_readers", link.sharedReaders())
                    .append("reader_cosine", link.cosine()).append("lift", link.lift()).append("tag_similarity", tagScore)
                    .append("shared_specific_tags", shared.stream().filter(s -> !s.getBoolean("generic")).limit(3).toList())
                    .append("shared_generic_tags", shared.stream().filter(s -> s.getBoolean("generic")).limit(3)
                            .map(s -> s.getString("tag")).toList()));
        }
        return out;
    }
}
