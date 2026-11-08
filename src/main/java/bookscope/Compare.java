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

/**
 * F2 - compare two reader profiles.
 *
 * <p>Comparer deux lecteurs (F2), en résumé :
 * <ol>
 *   <li>lire l'historique de chaque lecteur dans MongoDB (2 requêtes, une par lecteur) ;</li>
 *   <li>garder les livres que les deux ont notés ;</li>
 *   <li>calculer une corrélation de Pearson pondérée sur ces livres ;</li>
 *   <li>la réduire si les livres communs sont peu nombreux (confiance) ;</li>
 *   <li>donner les livres où ils sont le plus d'accord et le plus en désaccord.</li>
 * </ol>
 * Seule l'étape 1 parle à MongoDB : tout le reste est du calcul en Java.
 */
public final class Compare {
    /** En dessous de 5 livres communs, on refuse de calculer : le résultat ne voudrait rien dire. */
    public static final int MIN_COMMON = 5;
    /** Shared books needed before the similarity is used at full strength. */
    // Avec 25 livres communs ou plus, la confiance vaut 1 ; avec 10 livres, elle vaut 10/25 = 0,4.
    public static final int FULL_CONFIDENCE = 25;

    private record Ranked(double key, int book) {
    }

    private Compare() {
    }

    public static Document compare(Store store, int userA, int userB, Integer truncateA, Integer truncateB, int k) {
        // Le catalogue (livres, tags, statistiques des livres) est déjà en mémoire : aucune requête ici.
        Catalog cat = store.catalog();

        // --- Étape 1 : les deux historiques (les SEULES requêtes MongoDB de F2) ---
        // store.history fait find({user_id: X}) trié par seq (ordre dans le temps).
        // En distribué, mongos envoie chaque requête uniquement au shard qui contient ce lecteur.
        // truncateA / truncateB : si on donne 5, on ne garde que les 5 PREMIÈRES notes du lecteur
        // (c'est le cas « historique court » que le jury peut demander).
        List<Store.Rating> ha = store.history(userA, truncateA);
        List<Store.Rating> hb = store.history(userB, truncateB);
        if (ha.isEmpty() || hb.isEmpty()) {
            int missing = ha.isEmpty() ? userA : userB;
            return new Document("service", "F2").append("status", "not_found")
                    .append("message", "reader " + missing + " has no stored ratings");
        }

        // On range chaque historique dans une table livre -> note, et on calcule la note moyenne.
        Map<Integer, Integer> ra = toMap(ha);
        Map<Integer, Integer> rb = toMap(hb);
        double meanA = mean(ra);
        double meanB = mean(rb);

        // --- Étape 2 : les livres en commun ---
        // common = livres notés par A ET par B (intersection) ; union = livres notés par A OU par B.
        Set<Integer> common = new TreeSet<>(ra.keySet());
        common.retainAll(rb.keySet());
        Set<Integer> union = new HashSet<>(ra.keySet());
        union.addAll(rb.keySet());

        List<Document> readers = List.of(
                new Document("user_id", userA).append("ratings", ra.size()).append("mean_rating", rnd(meanA, 3)).append("truncated_to", truncateA),
                new Document("user_id", userB).append("ratings", rb.size()).append("mean_rating", rnd(meanB, 3)).append("truncated_to", truncateB));
        // jaccard = communs / union : la part de livres partagés sur l'ensemble de leurs livres.
        Document overlap = new Document("shared_books", common.size())
                .append("jaccard", rnd((double) common.size() / union.size()))
                .append("share_of_smaller_history", rnd((double) common.size() / Math.min(ra.size(), rb.size())));

        // Moins de 5 livres communs : on répond « insufficient_overlap » au lieu d'inventer un chiffre.
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
        //
        // --- Étape 3 : la corrélation de Pearson pondérée ---
        // da = note de A moins sa moyenne : positif = « A a plus aimé que d'habitude », négatif = moins.
        // Si da et db ont le même signe, le produit da*db est positif : ils vont dans le même sens.
        // w = poids du livre = log((N+1)/(n+1)) : un livre lu par peu de lecteurs pèse plus lourd.
        // num additionne les accords/désaccords ; denA et denB servent à ramener le résultat entre -1 et +1.
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
        // Si un lecteur a mis la même note partout (den = 0), on ne peut pas diviser : corrélation 0.
        double correlation = denA != 0 && denB != 0 ? num / Math.sqrt(denA * denB) : 0.0;

        // --- Étape 4 : la confiance ---
        // Peu de livres communs = résultat fragile, donc on le réduit : strength = corrélation x confiance.
        double confidence = (double) Math.min(common.size(), FULL_CONFIDENCE) / FULL_CONFIDENCE;
        double strength = correlation * confidence;

        // --- Étape 5 : les exemples d'accords et de désaccords ---
        // Accord : même sens par rapport à leur moyenne, ou même note.
        // Désaccord : sens opposés, ou au moins 2 points d'écart.
        // On trie par poids du livre (la clé est négative pour trier du plus grand au plus petit).
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
        // L'étiquette lisible : >= 0,5 forte, >= 0,25 modérée, entre -0,25 et 0,25 faible, sinon opposée.
        String label = strength >= 0.5 ? "strong" : strength >= 0.25 ? "moderate" : strength > -0.25 ? "weak" : "opposed";
        return new Document("service", "F2").append("status", "ok").append("readers", readers).append("overlap", overlap)
                .append("similarity", new Document("weighted_correlation", rnd(correlation)).append("confidence", rnd(confidence))
                        .append("strength", rnd(strength)).append("label", label))
                .append("agreements", top(agreements, k, cat, ra, rb))
                .append("disagreements", top(disagreements, k, cat, ra, rb))
                .append("method", "popularity-weighted Pearson correlation of mean-centred ratings on shared books, "
                        + "scaled by min(shared, " + FULL_CONFIDENCE + ")/" + FULL_CONFIDENCE);
    }

    /** Historique -> table livre -> note (on garde l'ordre dans le temps). */
    private static Map<Integer, Integer> toMap(List<Store.Rating> history) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Store.Rating r : history) {
            out.put(r.bookId(), r.rating());
        }
        return out;
    }

    /** Note moyenne du lecteur, sur l'historique utilisé (tronqué ou non). */
    private static double mean(Map<Integer, Integer> ratings) {
        long sum = 0;
        for (int r : ratings.values()) {
            sum += r;
        }
        return (double) sum / ratings.size();
    }

    /** Une ligne du résultat : le livre, les deux notes, son nombre de lecteurs et son poids. */
    private static Document row(Catalog cat, Map<Integer, Integer> ra, Map<Integer, Integer> rb, int b) {
        return new Document("book_id", b).append("title", cat.title(b)).append("rating_a", ra.get(b)).append("rating_b", rb.get(b))
                .append("readers_of_book", cat.n(b)).append("weight", rnd(cat.bookWeight(b), 4));
    }

    /** Les k premiers après tri ; à égalité, le plus petit book_id passe devant (résultat stable). */
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
