package com.fathzer.healthtray;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Abstract base class for a periodic check task.
 * <BR>Each task has a name, a period (in seconds), and maintains its current state:
 * <ul>
 *   <li>{@link #getStatus() status} and {@link #getMessage() message} from the last check</li>
 *   <li>{@link #getLastCheck() lastCheck} timestamp (updated on every check)</li>
 *   <li>{@link #getLastChange() lastChange} timestamp (updated only on actual state transitions, not on first observation)</li>
 * </ul>
 * <BR>Listeners can subscribe to two events:
 * <ul>
 *   <li>{@link Listener#onCheckDone} &ndash; fired after every check (init or run)</li>
 *   <li>{@link Listener#onStateChange} &ndash; fired when the status changes (including the first observation)</li>
 * </ul>
 * Subclasses implement {@link #doInit()} and {@link #doRun()} to perform the actual check logic.
 */
public abstract class CheckTask {
    public record TaskResult(Status type, String message) {}

    public enum Status {
        OK,
        ERROR
    }

    /** Immutable snapshot of a task's state, used for persistence across restarts. */
    public record SavedState(
            String name,
            Status status,
            String message,
            Instant lastCheck,
            Instant lastChange
    ) {}

    /** Listener for {@link CheckTask} events. */
    public interface Listener {
        /** Called after every check (init or run). */
        default void onCheckDone(CheckTask task) {}
        /** Called when the status changes. {@code oldStatus} is {@code null} on the first observation. */
        default void onStateChange(CheckTask task, Status oldStatus, Status newStatus) {}
    }

    private final String name;
    private final long periodSeconds;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Status status;
    private volatile String message;
    private volatile Instant lastCheck;
    private volatile Instant lastChange;

    /** Creates a check task.
     * @param name the task name (displayed in notifications and the status window).
     * @param periodSeconds the period in seconds between two checks.
     */
    protected CheckTask(String name, long periodSeconds) {
        this.name = name;
        this.periodSeconds = periodSeconds;
    }

    public final String getName() { return name; }
    public final long getPeriod() { return periodSeconds; }
    public final Status getStatus() { return status; }
    public final String getMessage() { return message; }
    public final Instant getLastCheck() { return lastCheck; }
    public final Instant getLastChange() { return lastChange; }

    /** Adds a listener that will be notified of check and state change events. */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Removes a previously added listener. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Runs the initial check. Subclasses should not override this; implement {@link #doInit()} instead. */
    public final TaskResult init() {
        TaskResult result = doInit();
        updateState(result);
        return result;
    }

    /** Runs the initial check, then merges the result with the saved state.
     * <BR>Merge logic:
     * <ul>
     *   <li>If init succeeds (OK): all fields are restored from the saved state
     *       (status, message, lastCheck, lastChange).</li>
     *   <li>If init fails (ERROR): the error status and message from init are kept,
     *       lastCheck is taken from the saved state, and lastChange is taken from the saved state
     *       only if the saved status was already ERROR (otherwise null, since it's a new error).</li>
     * </ul>
     * @param saved the state saved at the previous shutdown.
     * @return the raw {@link TaskResult} from {@link #doInit()}.
     */
    public final TaskResult initWithRestore(SavedState saved) {
        TaskResult result = doInit();
        if (result.type() == Status.OK) {
            // Init succeeded: restore all saved values.
            this.message = saved.message();
            this.lastCheck = saved.lastCheck();
            this.lastChange = saved.lastChange();
            this.status = saved.status();
        } else {
            // Init failed: keep error + message from init, but use saved lastCheck
            // and saved lastChange only if the saved state was already ERROR.
            this.message = result.message();
            this.lastCheck = saved.lastCheck();
            this.lastChange = saved.status() == Status.ERROR ? saved.lastChange() : null;
            this.status = Status.ERROR;
        }
        // Fire events once with the merged state (first observation, oldStatus=null).
        for (Listener l : listeners) {
            l.onCheckDone(this);
        }
        for (Listener l : listeners) {
            l.onStateChange(this, null, this.status);
        }
        return result;
    }

    /** Runs a periodic check. Subclasses should not override this; implement {@link #doRun()} instead. */
    public final TaskResult run() {
        TaskResult result = doRun();
        updateState(result);
        return result;
    }

    /** Performs the initial check. Defaults to calling {@link #doRun()}. */
    protected TaskResult doInit() {
        return doRun();
    }

    /** Performs a periodic check. */
    protected abstract TaskResult doRun();

    /** Captures the current state as a {@link SavedState} for persistence. */
    public SavedState captureState() {
        return new SavedState(name, status, message, lastCheck, lastChange);
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
}
