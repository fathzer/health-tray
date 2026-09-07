package com.fathzer.healthtray;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Persists the state of {@link AbstractCheckTask}s across application restarts.
 * <BR>State is stored in a {@link Properties} file, with one entry per task.
 * Each task's fields (name, status, message, lastCheck, lastChange) are saved and can be restored
 * at startup via {@link AbstractCheckTask#initWithRestore(AbstractCheckTask.SavedState)}.
 */
class StatePersistence {
	private final Path file;

	/** Creates a persistence manager backed by the given file.
	 * @param file the file used to save/load state.
	 */
	public StatePersistence(Path file) {
		this.file = file;
	}

	/** Saves the current state of all tasks to the file.
	 * @param tasks the tasks whose state should be persisted.
	 * @throws IOException if the file cannot be written.
	 */
	public void save(List<AbstractCheckTask> tasks) throws IOException {
		Properties props = new Properties();
		props.setProperty("task.count", String.valueOf(tasks.size()));
		for (int i = 0; i < tasks.size(); i++) {
			AbstractCheckTask task = tasks.get(i);
			AbstractCheckTask.SavedState state = task.captureState();
			String prefix = "task." + i + ".";
			props.setProperty(prefix + "name", state.name() == null ? "" : state.name());
			props.setProperty(prefix + "status", state.status() == null ? "" : state.status().name());
			props.setProperty(prefix + "message", state.message() == null ? "" : state.message());
			props.setProperty(prefix + "lastCheck", state.lastCheck() == null ? "" : state.lastCheck().toString());
			props.setProperty(prefix + "lastChange", state.lastChange() == null ? "" : state.lastChange().toString());
			props.setProperty(prefix + "paused", String.valueOf(state.paused()));
		}
		try (Writer writer = Files.newBufferedWriter(file)) {
			props.store(writer, "HealthTray - CheckTask state");
		}
	}

	/** Loads the saved state from the file.
	 * @return a map of task name to {@link AbstractCheckTask.SavedState}, or an empty map if the file doesn't exist.
	 * @throws IOException if the file cannot be read.
	 */
	public Map<String, AbstractCheckTask.SavedState> load() throws IOException {
		if (!Files.exists(file)) {
			return new HashMap<>();
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			props.load(reader);
		}
		int count = Integer.parseInt(props.getProperty("task.count", "0"));
		Map<String, AbstractCheckTask.SavedState> result = new HashMap<>();
		for (int i = 0; i < count; i++) {
			String prefix = "task." + i + ".";
			String name = props.getProperty(prefix + "name", "");
			String statusStr = props.getProperty(prefix + "status", "");
			String message = props.getProperty(prefix + "message", "");
			String lastCheckStr = props.getProperty(prefix + "lastCheck", "");
			String lastChangeStr = props.getProperty(prefix + "lastChange", "");
			boolean paused = Boolean.parseBoolean(props.getProperty(prefix + "paused", "false"));
			AbstractCheckTask.Status status = statusStr.isEmpty() ? null : AbstractCheckTask.Status.valueOf(statusStr);
			Instant lastCheck = lastCheckStr.isEmpty() ? null : Instant.parse(lastCheckStr);
			Instant lastChange = lastChangeStr.isEmpty() ? null : Instant.parse(lastChangeStr);
			result.put(name, new AbstractCheckTask.SavedState(name, status, message, lastCheck, lastChange, paused));
		}
		return result;
	}
}
