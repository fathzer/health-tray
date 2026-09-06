package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.time.Instant;

/** A {@link CheckTask} that performs an action when a source has been updated since the last run.
 * <BR>The source is represented by a {@link TimestampSupplier}. On each run, the task compares the
 * source's timestamp with the timestamp of the previous run (available via {@link #getLastCheck()}).
 * If the source's timestamp is more recent, the {@link Action} is triggered.
 * <BR>The action is always triggered on the first run (when {@link #getLastCheck()} is {@code null},
 * i.e. no previous run has been recorded). This also applies after a restart with state restoration:
 * the action runs once on startup, then only when the source is updated.
 * <BR>When the source has not been updated since the last run, the task returns the previous
 * {@link TaskResult} unchanged.
 * <BR>The task reports {@link Status#ERROR ERROR} when the source is unavailable (supplier throws
 * {@link IOException}). The action's own {@link TaskResult} determines the status otherwise.
 * <BR>Example:
 * <pre>{@code
 * new UpdateActionTask("Reload config",
 *         TimestampSupplier.fromPath(Path.of("/etc/myapp/config.yml")),
 *         () -> {
 *             reloadConfig();
 *             return new TaskResult(Status.OK, "Config reloaded");
 *         },
 *         60);
 * }</pre>
 */
public class UpdateActionTask extends CheckTask {
	/** Functional interface representing the action to perform when the source is updated.
	 * <BR>Implementations return a {@link TaskResult} describing the outcome of the action.
	 * They may throw {@link IOException} to signal an unexpected failure.
	 */
	@FunctionalInterface
	public interface Action {
		/** Performs the action.
		 * @return the {@link TaskResult} describing the outcome of the action.
		 * @throws IOException if the action fails unexpectedly. */
		TaskResult run() throws IOException;
	}

	private final TimestampSupplier timestampSupplier;
	private final Action action;

	/** Creates an update action task.
	 * @param name the task name (displayed in notifications and the status window).
	 * @param timestampSupplier supplies the source's last update timestamp, or throws {@link IOException} if unavailable.
	 * @param action the action to perform when the source has been updated since the last run.
	 * @param periodSeconds the period in seconds between two checks.
	 */
	public UpdateActionTask(String name, TimestampSupplier timestampSupplier, Action action, long periodSeconds) {
		super(name, periodSeconds);
		this.timestampSupplier = timestampSupplier;
		this.action = action;
	}

	@Override
	protected TaskResult doRun() {
		Instant timestamp;
		try {
			timestamp = timestampSupplier.get();
		} catch (IOException e) {
			return new TaskResult(Status.ERROR, "Source unavailable");
		}
		// First run (no previous check recorded): always trigger the action.
		// Subsequent runs: trigger only if the source has been updated since the last run.
		Instant lastCheck = getLastCheck();
		if (lastCheck != null && !timestamp.isAfter(lastCheck)) {
			return new TaskResult(getStatus(), getMessage());
		}
		try {
			return action.run();
		} catch (IOException e) {
			return new TaskResult(Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}
