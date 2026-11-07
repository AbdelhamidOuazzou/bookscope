package bookscope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Shared thresholds and small numeric helpers. */
public final class Util {
    /** A rating >= LIKE is a positive evaluation. */
    public static final int LIKE = 4;

    private Util() {
    }

    /** Rounds scores so that both deployments print identical results. */
    public static double rnd(double x) {
        return rnd(x, 6);
    }

    public static double rnd(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Numeric order in which -0.0 and 0.0 are equal, unlike Double.compare. */
    public static int cmp(double a, double b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    public static double median(Collection<? extends Number> values) {
        List<Double> sorted = new ArrayList<>();
        for (Number v : values) {
            sorted.add(v.doubleValue());
        }
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    public static int toInt(Object number) {
        return ((Number) number).intValue();
    }
}
