package com.fathzer.healthtray;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fathzer.healthtray.AbstractCheckTask.Status;
import com.fathzer.healthtray.AbstractCheckTask.TaskResult;
import com.fathzer.healthtray.tasks.TimestampSupplier;
import com.fathzer.healthtray.tasks.UpdateActionTask;

/** Tests for {@link UpdateActionTask}.
 * <BR>Verifies the scenarios:
 * <ul>
 *   <li>First run (no previous check) &rarr; action is triggered</li>
 *   <li>Source updated since last run &rarr; action is triggered</li>
 *   <li>Source not updated since last run &rarr; previous TaskResult returned, action not triggered</li>
 *   <li>Source unavailable (supplier throws {@link IOException}) &rarr; ERROR, action not triggered</li>
 *   <li>Action throws {@link IOException} &rarr; ERROR with exception message</li>
 *   <li>Action returns ERROR &rarr; ERROR status propagated</li>
 * </ul>
 */
class UpdateActionTaskTest {

	@Test
	void firstRun_triggersAction() {
		AtomicInteger callCount = new AtomicInteger();
		TimestampSupplier supplier = Instant::now;
		UpdateActionTask task = new UpdateActionTask("Test", supplier,
				() -> { callCount.incrementAndGet(); return new TaskResult(Status.OK, "Action done"); },
				60);

		TaskResult result = task.run();

		assertEquals(Status.OK, result.type());
		assertEquals("Action done", result.message());
		assertEquals(1, callCount.get());
	}

	@Test
	void sourceUpdated_triggersAction() {
		AtomicInteger callCount = new AtomicInteger();
		AtomicReference<Instant> timestamp = new AtomicReference<>(Instant.now());
		UpdateActionTask task = new UpdateActionTask("Test", timestamp::get,
				() -> { callCount.incrementAndGet(); return new TaskResult(Status.OK, "Action done"); },
				60);

		// First run
		task.run();
		assertEquals(1, callCount.get());

		// Simulate source update: move timestamp forward beyond lastCheck
		timestamp.set(Instant.now().plus(Duration.ofSeconds(1)));
		TaskResult result = task.run();

		assertEquals(Status.OK, result.type());
		assertEquals("Action done", result.message());
		assertEquals(2, callCount.get());
	}

	@Test
	void sourceNotUpdated_returnsPreviousResult_doesNotTriggerAction() {
		AtomicInteger callCount = new AtomicInteger();
		Instant fixed = Instant.now();
		UpdateActionTask task = new UpdateActionTask("Test", () -> fixed,
				() -> { callCount.incrementAndGet(); return new TaskResult(Status.OK, "Action done"); },
				60);

		// First run
		task.run();
		assertEquals(1, callCount.get());

		// Second run: source timestamp unchanged, should not trigger action
		TaskResult result = task.run();

		assertEquals(Status.OK, result.type());
		assertEquals("Action done", result.message());
		assertEquals(1, callCount.get(), "Action should not be triggered when source is not updated");
	}

	@Test
	void sourceUnavailable_returnsError_doesNotTriggerAction() {
		AtomicInteger callCount = new AtomicInteger();
		TimestampSupplier supplier = () -> { throw new IOException("file not found"); };
		UpdateActionTask task = new UpdateActionTask("Test", supplier,
				() -> { callCount.incrementAndGet(); return new TaskResult(Status.OK, "Action done"); },
				60);

		TaskResult result = task.run();

		assertEquals(Status.ERROR, result.type());
		assertEquals("Source unavailable", result.message());
		assertEquals(0, callCount.get(), "Action should not be triggered when source is unavailable");
	}

	@Test
	void actionThrowsIOException_returnsError() {
		TimestampSupplier supplier = Instant::now;
		UpdateActionTask task = new UpdateActionTask("Test", supplier,
				() -> { throw new IOException("action failed"); },
				60);

		TaskResult result = task.run();

		assertEquals(Status.ERROR, result.type());
		assertTrue(result.message().contains("action failed"), "Expected message to contain 'action failed', got: " + result.message());
	}

	@Test
	void actionReturnsError_propagatesErrorStatus() {
		TimestampSupplier supplier = Instant::now;
		UpdateActionTask task = new UpdateActionTask("Test", supplier,
				() -> new TaskResult(Status.ERROR, "Custom error"),
				60);

		TaskResult result = task.run();

		assertEquals(Status.ERROR, result.type());
		assertEquals("Custom error", result.message());
	}

	@Test
	void sourceNotUpdated_afterErrorRun_returnsPreviousErrorResult() {
		AtomicInteger callCount = new AtomicInteger();
		Instant fixed = Instant.now();
		UpdateActionTask task = new UpdateActionTask("Test", () -> fixed,
				() -> {
					int n = callCount.incrementAndGet();
					return n == 1
							? new TaskResult(Status.ERROR, "First run error")
							: new TaskResult(Status.OK, "Should not happen");
				},
				60);

		// First run returns ERROR
		TaskResult first = task.run();
		assertEquals(Status.ERROR, first.type());
		assertEquals("First run error", first.message());
		assertEquals(1, callCount.get());

		// Second run: source unchanged, should return previous ERROR result without calling action
		TaskResult second = task.run();
		assertEquals(Status.ERROR, second.type());
		assertEquals("First run error", second.message());
		assertEquals(1, callCount.get(), "Action should not be triggered when source is not updated");
	}
}
