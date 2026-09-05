package com.fathzer.healthtray;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fathzer.healthtray.tasks.CheckTask;
import com.fathzer.healthtray.tasks.CheckTask.Listener;
import com.fathzer.healthtray.tasks.CheckTask.SavedState;
import com.fathzer.healthtray.tasks.CheckTask.Status;
import com.fathzer.healthtray.tasks.CheckTask.TaskResult;

/** Tests the merge logic of {@link CheckTask#initWithRestore(SavedState)}.
 * <BR>Verifies the four scenarios:
 * <ul>
 *   <li>init OK + saved OK  &rarr; restore all saved values</li>
 *   <li>init OK + saved ERROR &rarr; restore saved ERROR state</li>
 *   <li>init ERROR + saved OK  &rarr; keep init error, saved lastCheck, lastChange=null</li>
 *   <li>init ERROR + saved ERROR &rarr; keep init error, saved lastCheck, saved lastChange</li>
 * </ul>
 */
class CheckTaskInitRestoreTest {

    /** A simple CheckTask whose init/run results can be controlled per test. */
    private static class FakeTask extends CheckTask {
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
        return new SavedState("Test", status, message, lastCheck, lastChange);
    }

    @Test
    void initOk_savedOk_restoresAllSavedValues() {
        Instant savedCheck = Instant.parse("2026-01-01T10:00:00Z");
        Instant savedChange = Instant.parse("2026-01-01T09:00:00Z");
        SavedState saved = saved(Status.OK, "saved ok msg", savedCheck, savedChange);

        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        task.initWithRestore(saved);

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

        task.initWithRestore(saved);

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

        task.initWithRestore(saved);

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

        task.initWithRestore(saved);

        // Init failed: keep ERROR + message from init.
        assertEquals(Status.ERROR, task.getStatus());
        assertEquals("init failed again", task.getMessage());
        // lastCheck comes from saved state.
        assertEquals(savedCheck, task.getLastCheck());
        // lastChange comes from saved state because saved was already ERROR (persistent error).
        assertEquals(savedChange, task.getLastChange());
    }

    @Test
    void initWithRestore_firesOnCheckDoneAndOnStateChange() {
        SavedState saved = saved(Status.OK, "msg", Instant.parse("2026-01-01T10:00:00Z"), null);
        FakeTask task = new FakeTask("Test",
                new TaskResult(Status.OK, "init ok"),
                new TaskResult(Status.OK, "run ok"));

        List<String> events = new ArrayList<>();
        task.addListener(new Listener() {
            @Override
            public void onCheckDone(CheckTask t) {
                events.add("checkDone:" + t.getStatus());
            }
            @Override
            public void onStateChange(CheckTask t, Status oldStatus, Status newStatus) {
                events.add("stateChange:" + oldStatus + "->" + newStatus);
            }
        });

        task.initWithRestore(saved);

        // Should fire onCheckDone once and onStateChange once (first observation, oldStatus=null).
        assertTrue(events.contains("checkDone:OK"), "Expected onCheckDone with OK status");
        assertTrue(events.contains("stateChange:null->OK"), "Expected onStateChange null->OK");
        // Each event should fire exactly once.
        assertEquals(2, events.size(), "Expected exactly 2 events, got: " + events);
    }
}
