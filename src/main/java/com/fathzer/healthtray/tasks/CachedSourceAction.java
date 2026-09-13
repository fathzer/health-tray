package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;

import com.fathzer.healthtray.AbstractCheckTask.TaskResult;
import com.fathzer.healthtray.sources.InputStreamSupplier;

/** An {@link UpdateActionTask.Action} that caches the content of an {@link InputStreamSupplier}
 * into a secure temporary file, then delegates to an action created from that file.
 * <BR>The temporary file (and its private parent directory) are always deleted after the inner
 * action runs, regardless of success or failure.
 * <BR>This is useful when the source's content needs to be read multiple times or processed by
 * tools that require a file (e.g. a command-line utility, a Docker container import, etc.).
 * <BR>Example:
 * <pre>{@code
 * new CachedSourceAction(inputStreamSupplier, tempFile -> {
 *     // Process tempFile, e.g. pass it to an external command
 *     return new TaskResult(Status.OK, "Done");
 * });
 * }</pre>
 */
public class CachedSourceAction implements UpdateActionTask.Action {
	private final InputStreamSupplier inputStreamSupplier;
	private final Function<Path, UpdateActionTask.Action> actionFactory;

	/** Creates a {@link CachedSourceAction}.
	 * @param inputStreamSupplier supplies the source's content as an {@link InputStream}.
	 * @param actionFactory creates the {@link UpdateActionTask.Action} to run from the temporary
	 *        file containing the cached source's content.
	 */
	public CachedSourceAction(InputStreamSupplier inputStreamSupplier, Function<Path, UpdateActionTask.Action> actionFactory) {
		this.inputStreamSupplier = inputStreamSupplier;
		this.actionFactory = actionFactory;
	}

	@Override
	public TaskResult run() throws IOException {
		Path tempFile = null;
		try {
			tempFile = cacheSource();
			return actionFactory.apply(tempFile).run();
		} finally {
			deleteTempFile(tempFile);
		}
	}

	@SuppressWarnings("java:S5443")
	private Path cacheSource() throws IOException {
		Path tempDir;
		if (Path.of("").getFileSystem().supportedFileAttributeViews().contains("posix")) {
			var attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
			tempDir = Files.createTempDirectory("cached-source-", attr);
		} else {
			tempDir = Files.createTempDirectory("cached-source-");
		}
		Path tempFile;
		if (Path.of("").getFileSystem().supportedFileAttributeViews().contains("posix")) {
			var attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
			tempFile = Files.createTempFile(tempDir, "content-", ".tmp", attr);
		} else {
			tempFile = Files.createTempFile(tempDir, "content-", ".tmp");
		}
		try (OutputStream out = Files.newOutputStream(tempFile); InputStream in = inputStreamSupplier.open()) {
			in.transferTo(out);
		}
		return tempFile;
	}

	private void deleteTempFile(Path tempFile) {
		if (tempFile != null) {
			try {
				Files.deleteIfExists(tempFile);
				Files.deleteIfExists(tempFile.getParent());
			} catch (IOException e) {
				// Best-effort cleanup.
			}
		}
	}
}
