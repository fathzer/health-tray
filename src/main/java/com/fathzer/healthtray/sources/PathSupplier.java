package com.fathzer.healthtray.sources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** A {@link TimestampSupplier} and {@link InputStreamSupplier} backed by a local file {@link Path}.
 * <BR>The timestamp is the file's last modification time, as reported by
 * {@link Files#getLastModifiedTime(Path, java.nio.file.LinkOption...)}.
 * If the file does not exist or is not readable, an {@link IOException} is thrown.
 * <BR>The input stream opens a new stream on the file's content via {@link Files#newInputStream(Path, java.nio.file.OpenOption...)}.
 * The caller is responsible for closing the returned stream.
 * <BR>Example:
 * <pre>{@code
 * PathSupplier supplier = new PathSupplier(Path.of("/backup/latest.tar"));
 * Instant timestamp = supplier.get();
 * try (InputStream in = supplier.open()) {
 *     // read file content
 * }
 * }</pre>
 */
public class PathSupplier implements InputStreamSupplier, TimestampSupplier {

    private final Path path;

    /** Creates a {@link PathSupplier} backed by a local file.
     * @param path the file path to monitor (e.g. {@code Path.of("/backup/latest.tar")}).
     */
    public PathSupplier(Path path) {
        this.path = path;
    }

    @Override
    public InputStream open() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public Instant get() throws IOException {
        return Files.getLastModifiedTime(path).toInstant();
    }
}
