package bookscope.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Minimal CSV reader: comma separated, double quotes, line breaks inside quoted fields. */
public final class Csv {
    private Csv() {
    }

    private static String[] parse(String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    /** Calls the consumer with the fields of every record, header included. */
    public static void records(Path path, Consumer<String[]> consumer) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder pending = null;
            String line;
            while ((line = in.readLine()) != null) {
                String record = pending == null ? line : pending.append('\n').append(line).toString();
                if (record.chars().filter(c -> c == '"').count() % 2 == 1) {
                    pending = new StringBuilder(record);
                } else {
                    pending = null;
                    consumer.accept(parse(record));
                }
            }
        }
    }

    /** Calls the consumer with the fields of every record after the header. */
    public static void rows(Path path, Consumer<String[]> consumer) throws IOException {
        boolean[] first = {true};
        records(path, fields -> {
            if (first[0]) {
                first[0] = false;
            } else {
                consumer.accept(fields);
            }
        });
    }

    /** Calls the consumer with every record as a {column: value} map. */
    public static void maps(Path path, Consumer<Map<String, String>> consumer) throws IOException {
        String[][] header = new String[1][];
        records(path, fields -> {
            if (header[0] == null) {
                header[0] = fields;
                return;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header[0].length && i < fields.length; i++) {
                row.put(header[0][i], fields[i]);
            }
            consumer.accept(row);
        });
    }

    public static List<Map<String, Object>> readAsMaps(Path path) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        maps(path, row -> rows.add(new LinkedHashMap<>(row)));
        return rows;
    }
}
