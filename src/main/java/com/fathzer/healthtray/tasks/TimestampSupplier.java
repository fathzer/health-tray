package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Functional interface supplying the last update timestamp of a source.
 * <BR>Implementations should throw {@link IOException} when the source is unavailable
 * (e.g. file does not exist, network resource unreachable).
 */
@FunctionalInterface
public interface TimestampSupplier {
	/** Creates a {@link TimestampSupplier} backed by a file {@link Path}.
	 * <BR>The timestamp is the file's last modification time, as reported by
	 * {@link Files#getLastModifiedTime(Path, java.nio.file.LinkOption...)}.
	 * @param path the file whose last modification time represents the source's freshness.
	 * @return a {@link TimestampSupplier} returning the file's last modification time.
	 */
	static TimestampSupplier fromPath(Path path) {
		return () -> Files.getLastModifiedTime(path).toInstant();
	}

	/** @return the source's last update timestamp.
	 * @throws IOException if the source is unavailable. */
	Instant get() throws IOException;
}