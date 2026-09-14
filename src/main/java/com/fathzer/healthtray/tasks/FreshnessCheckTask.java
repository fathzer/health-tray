package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.fathzer.healthtray.AbstractCheckTask;
import com.fathzer.healthtray.sources.TimestampSupplier;

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
			return new TaskResult(Status.ERROR, "Stale: last update " + formatDuration(age) + " ago (max " + formatDuration(maxAge) + ")");
		}
		return new TaskResult(Status.OK, "Fresh: updated " + formatDuration(age) + " ago");
	}

	/** Formats a duration as a compact, human-readable string using US-style units.
	 * <BR>Only the two most significant non-zero parts are shown, e.g.:
	 * <ul>
	 *   <li>{@code 1d 2h} for 26 hours</li>
	 *   <li>{@code 3h 20min} for 3 hours 20 minutes</li>
	 *   <li>{@code 45min} for 45 minutes</li>
	 *   <li>{@code 30s} for 30 seconds</li>
	 * </ul>
	 * @param duration the duration to format (must be non-negative).
	 * @return a compact string representation. */
	private static String formatDuration(Duration duration) {
		long seconds = duration.getSeconds();
		long days = seconds / 86_400;
		long hours = (seconds % 86_400) / 3_600;
		long minutes = (seconds % 3_600) / 60;
		long secs = seconds % 60;
		StringBuilder sb = new StringBuilder();
		int parts = 0;
		if (days > 0) {
			sb.append(days).append("d");
			parts++;
		}
		if (hours > 0) {
			if (parts > 0) sb.append(' ');
			sb.append(hours).append("h");
			parts++;
		}
		if (minutes > 0 && parts < 2) {
			if (parts > 0) sb.append(' ');
			sb.append(minutes).append("min");
			parts++;
		}
		if (secs > 0 && parts < 2) {
			if (parts > 0) sb.append(' ');
			sb.append(secs).append("s");
		}
		return sb.isEmpty() ? "0s" : sb.toString();
	}
}
