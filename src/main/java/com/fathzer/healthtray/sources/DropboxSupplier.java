package com.fathzer.healthtray.sources;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.Metadata;
import com.fathzer.healthtray.sources.TimestampSupplier;

/** A {@link TimestampSupplier} and {@link InputStreamSupplier} backed by a file on Dropbox.
 * <BR>The timestamp is the file's server-side last modification time, as reported by the
 * Dropbox API. If the file does not exist, is not a file (e.g. a folder), or the API call
 * fails, an {@link IOException} is thrown.
 * <BR>The input stream downloads the file's content from Dropbox. It must be closed by the caller.
 * <BR><b>This class requires the Dropbox SDK ({@code com.dropbox.core:dropbox-core-sdk}) on the
 * classpath at runtime.</b> It is declared as an optional Maven dependency, so clients who do not
 * need Dropbox support are not forced to include it.
 * <BR>To use this supplier, you need an authenticated {@link DbxClientV2}. Creating one requires:
 * <ol>
 *   <li>Register an application on the <a href="https://www.dropbox.com/developers/apps">Dropbox App Console</a>
 *       to obtain an app key and secret.</li>
 *   <li>Obtain an access token. This typically involves an OAuth2 flow (authorization code or PKCE),
 *       unless you generate a scoped token directly from the App Console for testing purposes.</li>
 *   <li>Build the client:
 *     <pre>{@code
 * DbxRequestConfig config = DbxRequestConfig.newBuilder("your-app-name").build();
 * DbxClientV2 client = new DbxClientV2(config, accessToken);
 * }</pre>
 *   </li>
 * </ol>
 * The access token must have the {@code files.metadata.read} permission for timestamps
 * and {@code files.content.read} for content downloads.
 * <BR>Example:
 * <pre>{@code
 * ...
 * DropboxSupplier supplier = new DropboxSupplier(client, "/backup/latest.tar");
 * Instant timestamp = supplier.get();
 * try (InputStream in = supplier.get()) {
 *     // read file content
 * }
 * }</pre>
 * @see <a href="https://www.dropbox.com/developers/documentation/java">Dropbox Java SDK documentation</a>
 */
public class DropboxSupplier implements TimestampSupplier, InputStreamSupplier {
	private final DbxClientV2 client;
	private final String path;

	/** Creates a {@link DropboxSupplier} backed by a file on Dropbox.
	 * @param client the Dropbox client (authenticated).
	 * @param path the Dropbox path of the file (e.g. {@code "/backup/latest.tar"}).
	 */
	public DropboxSupplier(DbxClientV2 client, String path) {
		this.client = client;
		this.path = path;
	}

	@Override
	public Instant get() throws IOException {
		try {
			Metadata metadata = client.files().getMetadata(path);
			if (metadata instanceof FileMetadata file) {
				return file.getServerModified().toInstant();
			}
			throw new IOException("Not a file: " + path);
		} catch (DbxException e) {
			throw new IOException("Dropbox error: " + e.getMessage(), e);
		}
	}

	@Override
	public InputStream open() throws IOException {
		try {
			return client.files().download(path).getInputStream();
		} catch (DbxException e) {
			throw new IOException("Dropbox download failed: " + e.getMessage(), e);
		}
	}

	@Override
	public String toString() {
		return "Dropbox://" + path;
	}
}
