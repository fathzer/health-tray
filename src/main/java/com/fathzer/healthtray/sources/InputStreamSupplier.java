package com.fathzer.healthtray.sources;

import java.io.IOException;
import java.io.InputStream;

/** Functional interface opening an {@link InputStream} for a source.
 * <BR>Implementations should throw {@link IOException} when the source is unavailable
 * (e.g. file does not exist, network resource unreachable).
 */
@FunctionalInterface
public interface InputStreamSupplier {
	/** Opens an {@link InputStream} for the source's content.
	 * <BR>The caller is responsible for closing the returned stream.
	 * @return a new {@link InputStream} reading the source's content.
	 * @throws IOException if the source is unavailable. */
	InputStream open() throws IOException;
}
