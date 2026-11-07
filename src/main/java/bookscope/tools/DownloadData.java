package bookscope.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Download the Goodbooks-10k CSV files into data/raw/.
 *
 * <p>Source: https://github.com/zygmuntz/goodbooks-10k (files are not versioned in this repo).
 */
public final class DownloadData {
    private static final String BASE_URL = "https://raw.githubusercontent.com/zygmuntz/goodbooks-10k/master/";
    private static final List<String> FILES = List.of("books.csv", "ratings.csv", "to_read.csv", "book_tags.csv", "tags.csv");

    private DownloadData() {
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void run(Args args) throws IOException, NoSuchAlgorithmException {
        Path output = Path.of(args.get("--output", "data/raw"));
        Files.createDirectories(output);
        for (String name : FILES) {
            Path target = output.resolve(name);
            if (Files.exists(target) && !args.has("--force")) {
                System.out.println(name + ": already present");
            } else {
                System.out.println(name + ": downloading ...");
                try (InputStream in = URI.create(BASE_URL + name).toURL().openStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.out.printf("  %,12d bytes  sha256=%s%n", Files.size(target), sha256(target).substring(0, 16));
        }
    }
}
