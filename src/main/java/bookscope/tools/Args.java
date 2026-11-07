package bookscope.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line arguments: positional values and --options. */
public final class Args {
    private static final Set<String> NO_VALUE = Set.of("--json", "--tags", "--force", "--all-readers");
    private static final Set<String> TWO_VALUES = Set.of("--readers", "--f2", "--f4");

    public final List<String> positional = new ArrayList<>();
    private final Map<String, List<String>> options = new HashMap<>();

    public Args(String[] argv) {
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("-")) {
                positional.add(token);
                continue;
            }
            int arity = NO_VALUE.contains(token) ? 0 : TWO_VALUES.contains(token) ? 2 : 1;
            if (i + arity >= argv.length) {
                throw new IllegalArgumentException("missing value after " + token);
            }
            options.put(token, new ArrayList<>(List.of(argv).subList(i + 1, i + 1 + arity)));
            i += arity;
        }
    }

    public boolean has(String name) {
        return options.containsKey(name);
    }

    public String get(String name, String fallback) {
        return has(name) ? options.get(name).get(0) : fallback;
    }

    public int getInt(String name, int fallback) {
        return has(name) ? Integer.parseInt(options.get(name).get(0)) : fallback;
    }

    public Integer getInteger(String name) {
        return has(name) ? Integer.valueOf(options.get(name).get(0)) : null;
    }

    /** The two values of an option such as --readers FIRST LAST. */
    public int[] getPair(String name, int first, int second) {
        return has(name) ? new int[] {Integer.parseInt(options.get(name).get(0)), Integer.parseInt(options.get(name).get(1))}
                : new int[] {first, second};
    }

    /** A comma separated list of integers. */
    public List<Integer> getInts(String name) {
        List<Integer> out = new ArrayList<>();
        if (has(name)) {
            for (String part : options.get(name).get(0).split(",")) {
                if (!part.isBlank()) {
                    out.add(Integer.valueOf(part.strip()));
                }
            }
        }
        return out;
    }
}
