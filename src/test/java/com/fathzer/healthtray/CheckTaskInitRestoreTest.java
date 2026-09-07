package com.fathzer.healthtray;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fathzer.healthtray.AbstractCheckTask.Listener;
import com.fathzer.healthtray.AbstractCheckTask.SavedState;
import com.fathzer.healthtray.AbstractCheckTask.Status;
import com.fathzer.healthtray.AbstractCheckTask.TaskResult;

/** Tests the merge logic of {@link AbstractCheckTask#init(Optional)}.
 * <BR>Verifies the scenarios:
 * <ul>
 *   <li>init OK + saved OK  &rarr; restore all saved values</li>
 *   <li>init OK + saved ERROR &rarr; restore saved ERROR state</li>
 *   <li>init ERROR + saved OK  &rarr; keep init error, saved lastCheck, lastChange=null</li>
 *   <li>init ERROR + saved ERROR &rarr; keep init error, saved lastCheck, saved lastChange</li>
 *   <li>init OK + no saved state &rarr; status remains null, lastCheck remains null</li>
 * </ul>
 */
class CheckTaskInitRestoreTest {

    /** A simple CheckTask whose init/run results can be controlled per test. */
    private static class FakeTask extends AbstractCheckTask {
        private final TaskResult initResult;
        private final TaskResult runResult;

        FakeTask(String name, TaskResult initResult, TaskResult runResult) {
            super(name, 10);
            this.initResult = initResult;
            this.runResult = runResult;
        }

        @Override
        protected TaskResult doInit() {
            return initResult;
        }

        @Override
        protected TaskResult doRun() {
            return runResult;
        }
    }

    private static SavedState saved(Status status, String message, Instant lastCheck, Instant lastChange) {
        return new SavedState("Test", status, message, lastCheck, lastChange, false);
    }

    @Test
    void initOk_savedOk_restoresAllSavedValues() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        Instant savedChange = Instant.parse("2026-01-01T09:00:00Z");
        SavedState saved = saved(Status.OK, "saved ok msg", savedCheck, savedChange);

        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        task.init(saved);

        assertEquals(Status.OK, task.getStatus());
        assertEquals("saved ok msg", task.getMessage());
        assertEquals(savedCheck, task.getLastCheck());
        assertEquals(savedChange, task.getLastChange());
    }

    @Test
    void initOk_savedError_restoresSavedErrorState() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        Instant savedChange = Instant.parse("2026-01-01T09:00:00Z");
        SavedState saved = saved(Status.ERROR, "saved error msg", savedCheck, savedChange);

        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        task.init(saved);

        // Init succeeded, so we restore the saved ERROR state.
        assertEquals(Status.ERROR, task.getStatus());
        assertEquals("saved error msg", task.getMessage());
        assertEquals(savedCheck, task.getLastCheck());
        assertEquals(savedChange, task.getLastChange());
    }

    @Test
    void initError_savedOk_keepsInitError_lastChangeNull() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        // saved was OK, so lastChange from saved is irrelevant (should not be used).
        Instant savedChange = Instant.parse("2026-01-01T08:00:00Z");
        SavedState saved = saved(Status.OK, "saved ok msg", savedCheck, savedChange);

        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.ERROR, "init failed"),
                new TaskResult(Status.OK, "run ok"));

        task.init(saved);

        // Init failed: keep ERROR + message from init.
        assertEquals(Status.ERROR, task.getStatus());
        assertEquals("init failed", task.getMessage());
        // lastCheck comes from saved state.
        assertEquals(savedCheck, task.getLastCheck());
        // lastChange is null because saved state was OK (new error, no prior error).
        assertNull(task.getLastChange());
    }

    @Test
    void initError_savedError_keepsInitError_restoresSavedLastChange() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        Instant savedChange = Instant.parse("2026-01-01T07:00:00Z");
        SavedState saved = saved(Status.ERROR, "saved error msg", savedCheck, savedChange);

        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.ERROR, "init failed again"),
                new TaskResult(Status.OK, "run ok"));

        task.init(saved);

        // Init failed: keep ERROR + message from init.
        assertEquals(Status.ERROR, task.getStatus());
        assertEquals("init failed again", task.getMessage());
        // lastCheck comes from saved state.
        assertEquals(savedCheck, task.getLastCheck());
        // lastChange comes from saved state because saved was already ERROR (persistent error).
        assertEquals(savedChange, task.getLastChange());
    }

    @Test
    void initOk_noSavedState_leavesStatusAndLastCheckNull() {
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        task.init(null);

        // Init succeeded with no saved state: nothing was set, no check was done.
        assertNull(task.getStatus());
        assertNull(task.getLastCheck());
        assertNull(task.getMessage());
        assertNull(task.getLastChange());
        assertEquals(AbstractCheckTask.ActivationState.RUNNING, task.getActivationState(), "Task should be RUNNING after successful init");
    }

    @Test
    void initError_notInited() {
        SavedState saved = saved(Status.OK, "msg", Instant.parse("2026-01-01T10:00:00Z"), null);
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.ERROR, "init failed"),
                new TaskResult(Status.OK, "run ok"));

        try (LogSilencer s = LogSilencer.silence(AbstractCheckTask.class)) {
            task.init(saved);
        }

        assertEquals(AbstractCheckTask.ActivationState.STOPPED, task.getActivationState(), "Task should be STOPPED after failed init");
    }

    @Test
    void initException_catchesAndMarksAsNotInited() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        SavedState saved = saved(Status.OK, "saved ok msg", savedCheck, null);
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "should not be used"),
                new TaskResult(Status.OK, "run ok")) {
            @Override
            protected TaskResult doInit() {
                throw new RuntimeException("Boom");
            }
        };

        List<String> events = new ArrayList<>();
        task.addListener(listener(events));

        try (LogSilencer s = LogSilencer.silence(AbstractCheckTask.class)) {
            task.init(saved);
        }

        // Init threw: task should be in ERROR, not inited, lastCheck preserved from saved state.
        assertEquals(AbstractCheckTask.ActivationState.STOPPED, task.getActivationState(), "Task should be STOPPED after init exception");
        assertEquals(Status.ERROR, task.getStatus());
        assertTrue(task.getMessage().contains("Boom"), "Expected message to contain 'Boom', got: " + task.getMessage());
        assertEquals(savedCheck, task.getLastCheck(), "lastCheck should be preserved from saved state");
        assertTrue(events.contains("stateChange:OK->ERROR"), "Expected onStateChange OK->ERROR, got: " + events);
    }

    @Test
    void initOk_firesNoEvent() {
        SavedState saved = saved(Status.OK, "msg", Instant.parse("2026-01-01T10:00:00Z"), null);
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        List<String> events = new ArrayList<>();
        task.addListener(listener(events));

        task.init(saved);

        // Init succeeded and restored state is the same as saved: no events should fire.
        assertTrue(events.isEmpty(), "Expected no events, got: " + events);
    }

    @Test
    void initError_firesOnStateChangeOnly() {
        SavedState saved = saved(Status.OK, "msg", Instant.parse("2026-01-01T10:00:00Z"), null);
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.ERROR, "init failed"),
                new TaskResult(Status.OK, "run ok"));

        List<String> events = new ArrayList<>();
        task.addListener(listener(events));

        task.init(saved);

        // Init failed: onStateChange should fire (OK -> ERROR), but not onCheckDone (no check was done).
        assertTrue(events.contains("stateChange:OK->ERROR"), "Expected onStateChange OK->ERROR, got: " + events);
        assertFalse(events.stream().anyMatch(e -> e.startsWith("checkDone:")), "Expected no onCheckDone event, got: " + events);
        assertEquals(1, events.size(), "Expected exactly 1 event, got: " + events);
    }

    private static Listener listener(List<String> events) {
        return new Listener() {
            @Override
            public void onCheckDone(AbstractCheckTask t) {
                events.add("checkDone:" + t.getStatus());
            }
            @Override
            public void onStateChange(AbstractCheckTask t, Status oldStatus, Status newStatus) {
                events.add("stateChange:" + oldStatus + "->" + newStatus);
            }
        };
    }
}
