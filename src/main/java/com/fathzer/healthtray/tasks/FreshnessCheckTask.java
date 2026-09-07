package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.fathzer.healthtray.AbstractCheckTask;

/** A {@link AbstractCheckTask} that verifies a source has been updated more recently than a maximum age.
 * <BR>The source is represented by a {@link TimestampSupplier} that returns the source's last
 * update timestamp. If the source is unavailable, the supplier throws {@link IOException}.
 * <BR>The task reports {@link Status#OK OK} when the source's timestamp is within the allowed
 * age (i.e. {@code now - timestamp <= maxAge}), and {@link Status#ERROR ERROR} when the source
 * is older than the maximum age or when the source is unavailable (supplier throws {@link IOException}).
 * <BR>Example using a {@link java.nio.file.Path}:
 * <pre>{@code
 * new FreshnessCheckTask("Backup freshness",
 *         TimestampSupplier.fromPath(Path.of("/var/backup/latest.tar")),
 *         Duration.ofHours(24), 3600);
 * }</pre>
 */
public class FreshnessCheckTask extends AbstractCheckTask {
	private final TimestampSupplier timestampSupplier;
	private final Duration maxAge;

	/** Creates a freshness check task.
	 * @param name the task name (displayed in notifications and the status window).
	 * @param timestampSupplier supplies the source's last update timestamp, or throws {@link IOException} if unavailable.
	 * @param maxAge the maximum age allowed for the source before it is considered stale.
	 * @param periodSeconds the period in seconds between two checks.
	 */
	public FreshnessCheckTask(String name, TimestampSupplier timestampSupplier, Duration maxAge, long periodSeconds) {
		super(name, periodSeconds);
		this.timestampSupplier = timestampSupplier;
		this.maxAge = maxAge;
	}

	@Override
	protected TaskResult doRun() {
		final Instant timestamp;
		try {
			timestamp = timestampSupplier.get();
		} catch (IOException e) {
			return new TaskResult(Status.ERROR, "Source unavailable");
		}
		Instant now = Instant.now();
		Duration age = Duration.between(timestamp, now);
		if (age.compareTo(maxAge) > 0) {
			return new TaskResult(Status.ERROR, "Stale: last update " + age.toHours() + "h ago (max " + maxAge.toHours() + "h)");
		}
		return new TaskResult(Status.OK, "Fresh: updated " + age.toMinutes() + "min ago");
	}
}
