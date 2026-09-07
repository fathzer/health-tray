package com.fathzer.healthtray;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;

/** Abstract base class for a periodic check task.
 * <BR>Each task has a name, a period (in seconds), and maintains its current state:
 * <ul>
 *   <li>{@link #getStatus() status} and {@link #getMessage() message} from the last check</li>
 *   <li>{@link #getLastCheck() lastCheck} timestamp (updated on every check)</li>
 *   <li>{@link #getLastChange() lastChange} timestamp (updated only on actual state transitions, not on first observation)</li>
 * </ul>
 * <BR>Listeners can subscribe to two events:
 * <ul>
 *   <li>{@link Listener#onCheckDone} &ndash; fired after every run</li>
 *   <li>{@link Listener#onStateChange} &ndash; fired when the status changes</li>
 * </ul>
 * Subclasses implement {@link #doInit()} and {@link #doRun()} to perform the actual check logic.
 */
public abstract class AbstractCheckTask {
	private static final Logger LOGGER = Logger.getLogger(AbstractCheckTask.class.getName());
    /** A predefined {@link TaskResult} with {@link Status#OK} and an empty message. */
	public static final TaskResult OK = new TaskResult(Status.OK, "");

	/** The result of a check: a status and a message.
	 * @param type the status of the check.
	 * @param message a short human-readable message describing the result. */
    public record TaskResult(Status type, String message) {}

	/** The status of a check task. */
    public enum Status {
		/** The check succeeded. */
        OK,
		/** The check failed. */
        ERROR
    }

	/** The activation state of a task (runtime state, not persisted).
	 * <BR>This reflects the current lifecycle stage of the task:
	 * <ul>
	 *   <li>{@link #STOPPED} &ndash; the task is not running (either paused by the user, or initialization failed).</li>
	 *   <li>{@link #INITIALIZING} &ndash; the task is currently being initialized.</li>
	 *   <li>{@link #RUNNING} &ndash; the task is initialized and scheduled for periodic checks.</li>
	 * </ul> */
	public enum ActivationState {
		/** The task is not running (either paused by the user, or initialization failed). */
		STOPPED,
		/** The task is currently being initialized. */
		INITIALIZING,
		/** The task is initialized and scheduled for periodic checks. */
		RUNNING
	}

    /** Immutable snapshot of a task's state, used for persistence across restarts. */
    record SavedState(
            String name,
            Status status,
            String message,
            Instant lastCheck,
            Instant lastChange,
            boolean paused
    ) {}

    /** Listener for {@link AbstractCheckTask} events. */
    public interface Listener {
        /** Called after every run.
         * @param task the task that was checked. */
        default void onCheckDone(AbstractCheckTask task) {}
        /** Called when the status changes.
         * @param task the task whose status changed.
         * @param oldStatus the previous status, or {@code null} on the first observation.
         * @param newStatus the new status. */
        default void onStateChange(AbstractCheckTask task, Status oldStatus, Status newStatus) {}
        /** Called when the task's activation state changes (initialized or paused/resumed).
         * <BR>This is fired when a task becomes initialized (after a successful init),
         * when it is paused, or when it is resumed (which triggers a re-init).
         * @param task the task whose activation state changed. */
        default void onActivationChanged(AbstractCheckTask task) {}
    }

    private final String name;
    private final long periodSeconds;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Status status;
    private volatile String message;
    private volatile Instant lastCheck;
    private volatile Instant lastChange;
    private volatile ActivationState activationState = ActivationState.STOPPED;
    private volatile boolean paused;

    /** Creates a check task.
     * @param name the task name (displayed in notifications and the status window).
     * @param periodSeconds the period in seconds between two checks.
     */
    protected AbstractCheckTask(String name, long periodSeconds) {
        this.name = name;
        this.periodSeconds = periodSeconds;
    }

	/** Gets the task name.
	 * @return the task name (displayed in notifications and the status window). */
    public final String getName() { return name; }
	/** Gets the period between two checks.
	 * @return the period in seconds between two checks. */
    public final long getPeriod() { return periodSeconds; }
	/** Gets the current status.
	 * @return the current status, or {@code null} if no check has been run yet. */
    public final Status getStatus() { return status; }
	/** Gets the message from the last check.
	 * @return the message from the last check, or {@code null} if no check has been run yet. */
    public final String getMessage() { return message; }
	/** Gets the timestamp of the last check.
	 * @return the timestamp of the last check, or {@code null} if no check has been run yet. */
    public final Instant getLastCheck() { return lastCheck; }
	/** Gets the timestamp of the last status change.
	 * @return the timestamp of the last status change, or {@code null} if no change has occurred. */
    public final Instant getLastChange() { return lastChange; }
	/** Returns the current activation state of this task (runtime state, not persisted).
	 * @return the current {@link ActivationState}. */
    public final ActivationState getActivationState() { return activationState; }
	/** Returns whether this task is paused (user intent, persisted across restarts).
	 * <BR>A paused task is not initialized, not scheduled, and its state is preserved across runs.
	 * @return {@code true} if the task is paused. */
    public final boolean isPaused() { return paused; }
	/** Stops the task: marks it as paused and sets the activation state to {@link ActivationState#STOPPED}.
	 * <BR>Fires {@link Listener#onActivationChanged}. The caller is responsible for cancelling any
	 * scheduled execution.
	 * <BR>This method must be called on the EDT. */
    final void stop() {
        this.paused = true;
        this.activationState = ActivationState.STOPPED;
        fireActivationChanged();
    }
	/** Resumes the task: clears the paused flag and sets the activation state to {@link ActivationState#INITIALIZING}.
	 * <BR>Fires {@link Listener#onActivationChanged} so that listeners can immediately reflect the
	 * resume (e.g. switch the button to pause). The actual re-initialization is handled by the caller
	 * (typically by calling {@link #init(SavedState)} asynchronously).
	 * <BR>This method must be called on the EDT. */
    final void resume() {
        this.paused = false;
        this.activationState = ActivationState.INITIALIZING;
        fireActivationChanged();
    }

    /** Adds a listener that will be notified of check and state change events.
     * @param listener the listener to add. */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Removes a previously added listener.
     * @param listener the listener to remove. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Initializes the task, optionally restoring a previously saved state.
     * <BR>If a saved state is provided, it is restored first (so that {@code lastCheck} is available
     * even if {@link #doInit()} throws an exception). Then {@link #doInit()} is called:
     * <ul>
     *   <li>If init succeeds (OK): the restored state (if any) is kept as-is.
     *       The activation state is set to {@link ActivationState#RUNNING} and {@link Listener#onActivationChanged} is fired.</li>
     *   <li>If init fails (ERROR or exception): the error status and message are kept,
     *       lastCheck remains from the saved state (or {@code null} if no saved state),
     *       and lastChange is taken from the saved state only if the saved status was already ERROR
     *       (otherwise null, since it's a new error). {@link Listener#onStateChange} is fired.
     *       The activation state is set to {@link ActivationState#STOPPED} and {@link Listener#onActivationChanged} is fired.</li>
     * </ul>
     * <BR>Note: init is not a check, so {@code lastCheck} is never set by this method
     * (it is only preserved from the saved state).
     * @param saved the saved state from the previous shutdown, or null if starting fresh.
     * @return the {@link TaskResult} from {@link #doInit()}, or an ERROR result if {@link #doInit()} threw an exception.
     */
    public final TaskResult init(SavedState saved) {
        if (SwingUtilities.isEventDispatchThread()) {
            LOGGER.warning("init() called on the EDT for task " + getName() + " — this may block the UI");
        }
        // 1. Restore saved state if present.
        Status oldStatus = null;
        if (saved != null) {
            this.message = saved.message();
            this.lastCheck = saved.lastCheck();
            this.lastChange = saved.lastChange();
            this.status = saved.status();
            this.paused = saved.paused();
            oldStatus = saved.status();
        }

        if (this.paused) {
        	LOGGER.info("Skipping initialization of paused task " + getName());
            this.activationState = ActivationState.STOPPED;
        	return new TaskResult(Status.OK, "Task is paused");
        }

        LOGGER.info("Initializing task " + getName());
        this.activationState = ActivationState.INITIALIZING;
        fireActivationChanged();

        // 2. Run init.
        TaskResult result;
        try {
            result = doInit();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Error during init of task " + getName());
            result = new TaskResult(Status.ERROR, "Initialization failed: " + e.getMessage());
        }
        if (result.type() != Status.OK) {
            // Init failed: keep error + message from init, lastCheck is already restored (or null).
            this.message = result.message();
            this.lastChange = (oldStatus == Status.ERROR) ? this.lastChange : null;
            this.status = Status.ERROR;
            this.activationState = ActivationState.STOPPED;
            // Notify the state change (old status -> ERROR) and activation change.
            for (Listener l : listeners) {
                l.onStateChange(this, oldStatus, Status.ERROR);
            }
            fireActivationChanged();
        } else {
            // Init succeeded.
            this.activationState = ActivationState.RUNNING;
            fireActivationChanged();
        }
        return result;
    }

    /** Runs a periodic check. Subclasses should not override this; implement {@link #doRun()} instead.
     * @return the {@link TaskResult} from {@link #doRun()}, or an ERROR result if {@link #doRun()} threw an exception. */
    public final TaskResult run() {
        if (SwingUtilities.isEventDispatchThread()) {
            LOGGER.warning("run() called on the EDT for task " + getName() + " — this may block the UI");
        }
        LOGGER.info("Running task " + getName());
        TaskResult result;
        try {
            result = doRun();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Error during run of task " + getName());
            result = new TaskResult(Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        updateState(result);
        return result;
    }

    /** Performs the initial check. Defaults does nothing and returns OK.
     * @return the {@link TaskResult} of the initial check. */
    protected TaskResult doInit() {
        return OK;
    }

    /** Performs a periodic check.
     * @return the {@link TaskResult} of the check. */
    protected abstract TaskResult doRun();

    /** Captures the current state as a {@link SavedState} for persistence. */
    SavedState captureState() {
        return new SavedState(name, status, message, lastCheck, lastChange, paused);
    }

    private void updateState(TaskResult result) {
        Instant now = Instant.now();
        Status oldStatus = this.status;
        this.message = result.message();
        this.lastCheck = now;
        // lastChange is only set on an actual transition (not on the first observation).
        if (oldStatus != null && oldStatus != result.type()) {
            this.lastChange = now;
        }
        this.status = result.type();
        for (Listener l : listeners) {
            l.onCheckDone(this);
        }
        if (oldStatus != result.type()) {
            for (Listener l : listeners) {
                l.onStateChange(this, oldStatus, result.type());
            }
        }
    }

    /** Notifies listeners that the activation state (inited/paused) has changed. */
    private void fireActivationChanged() {
        for (Listener l : listeners) {
            l.onActivationChanged(this);
        }
    }
}
