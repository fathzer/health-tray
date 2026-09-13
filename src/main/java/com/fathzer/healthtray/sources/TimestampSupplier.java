package com.fathzer.healthtray.sources;

import java.io.IOException;
import java.time.Instant;

/** Functional interface supplying the last update timestamp of a source.
 * <BR>Implementations should throw {@link IOException} when the source is unavailable
 * (e.g. file does not exist, network resource unreachable).
 */
@FunctionalInterface
public interface TimestampSupplier {
	/** Returns the source's last update timestamp.
	 * @return the source's last update timestamp.
	 * @throws IOException if the source is unavailable. */
	Instant get() throws IOException;
}