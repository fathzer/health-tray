package com.fathzer.healthtray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dorkbox.notify.Notify;
import dorkbox.notify.Position;
import dorkbox.notify.Theme;
import kotlin.Unit;

/** Manages the lifecycle of error/recovery notifications for {@link CheckTask}s.
 * <BR>Subscribes to each task's {@link CheckTask.Listener#onStateChange} event:
 * <ul>
 *   <li>Transition to {@link CheckTask.Status#ERROR}: shows a persistent notification (until recovery or manual close).</li>
 *   <li>Transition from ERROR to {@link CheckTask.Status#OK}: closes the error notification and shows a short "{@code <name> is ok}" notification.</li>
 * </ul>
 */
public class NotificationManager implements CheckTask.Listener {
	/** Duration (in ms) of the "recovered" notification shown when a task transitions back to OK. */
	private static final int RECOVERY_DURATION_MS = 10_000;

	private final List<CheckTask> tasks;
	private volatile Runnable onNotificationClick = () -> {};
	private final Map<String, Notify> activeErrors = new ConcurrentHashMap<>();

	/** Creates a notification manager and subscribes to the given tasks.
	 * <BR>Use {@link #setOnNotificationClick(Runnable)} to set the click handler after construction
	 * (useful when the click handler depends on another component that depends on this manager).
	 * @param tasks the tasks to monitor for state changes.
	 */
	public NotificationManager(List<CheckTask> tasks) {
		this.tasks = tasks;
		for (CheckTask task : tasks) {
			task.addListener(this);
		}
	}

	/** Sets the action invoked when the user clicks on any notification. */
	public void setOnNotificationClick(Runnable onNotificationClick) {
		this.onNotificationClick = onNotificationClick;
	}

	@Override
	public void onStateChange(CheckTask task, CheckTask.Status oldStatus, CheckTask.Status newStatus) {
		boolean wasError = oldStatus == CheckTask.Status.ERROR;
		boolean isError = newStatus == CheckTask.Status.ERROR;
		if (isError && !wasError) {
			showError(task.getName(), task.getMessage());
		} else if (!isError && wasError) {
			closeError(task.getName());
			showRecovery(task.getName());
		}
	}

	private void showError(String name, String message) {
		Notify notify = Notify.Companion.create()
				.title(name)
				.text(message)
				.theme(Theme.Companion.getDefaultDark())
				.position(Position.BOTTOM_RIGHT)
				.onCloseAction(n -> {
					// Remove from active map when the user manually closes the notification,
					// so that restore() can re-show it later.
					activeErrors.remove(name);
					return Unit.INSTANCE;
				})
				.onClickAction(n -> {
					onNotificationClick.run();
					return Unit.INSTANCE;
				});
		notify.showError();
		activeErrors.put(name, notify);
	}

	private void closeError(String name) {
		Notify notify = activeErrors.remove(name);
		if (notify != null) {
			notify.close();
		}
	}

	private void showRecovery(String name) {
		Notify notify = Notify.Companion.create()
				.title(name)
				.text(name + " is ok")
				.theme(Theme.Companion.getDefaultDark())
				.position(Position.BOTTOM_RIGHT)
				.hideAfter(RECOVERY_DURATION_MS)
				.onClickAction(n -> {
					onNotificationClick.run();
					return Unit.INSTANCE;
				});
		notify.showInformation();
	}

	/** Re-shows error notifications for all tasks currently in {@link CheckTask.Status#ERROR}.
	 * <BR>This is useful when the user has manually closed a notification and wants to see it again.
	 * Tasks whose error notification is still visible are not duplicated.
	 */
	public void restore() {
		for (CheckTask task : tasks) {
			if (task.getStatus() == CheckTask.Status.ERROR && !activeErrors.containsKey(task.getName())) {
				showError(task.getName(), task.getMessage());
			}
		}
	}

	/** Closes all active error notifications (e.g. on application shutdown). */
	public void closeAll() {
		for (Notify notify : activeErrors.values()) {
			notify.close();
		}
		activeErrors.clear();
	}
}
