package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.Metadata;

/** A {@link TimestampSupplier} backed by a file on Dropbox.
 * <BR>The timestamp is the file's server-side last modification time, as reported by the
 * Dropbox API. If the file does not exist, is not a file (e.g. a folder), or the API call
 * fails, an {@link IOException} is thrown.
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
 * The access token must have the {@code files.metadata.read} permission to use this supplier.
 * <BR>Example:
 * <pre>{@code
 * ...
 * TimestampSupplier supplier = new DropboxTimestampSupplier(client, "/backup/latest.tar");
 * }</pre>
 * @see <a href="https://www.dropbox.com/developers/documentation/java">Dropbox Java SDK documentation</a>
 */
public class DropboxTimestampSupplier implements TimestampSupplier {
	private final DbxClientV2 client;
	private final String path;

	/** Creates a {@link TimestampSupplier} backed by a file on Dropbox.
	 * @param client the Dropbox client (authenticated).
	 * @param path the Dropbox path of the file to monitor (e.g. {@code "/backup/latest.tar"}).
	 */
	public DropboxTimestampSupplier(DbxClientV2 client, String path) {
		this.client = client;
		this.path = path;
	}

	@Override
	public Instant get() throws IOException {
		try {
			Metadata metadata = client.files().getMetadata(path);
			if (metadata instanceof FileMetadata file) {
				Date modified = file.getServerModified();
				return modified.toInstant();
			}
			throw new IOException("Not a file: " + path);
		} catch (DbxException e) {
			throw new IOException("Dropbox error: " + e.getMessage(), e);
		}
	}

	@Override
	public String toString() {
		return "Dropbox://" + path;
	}
}
