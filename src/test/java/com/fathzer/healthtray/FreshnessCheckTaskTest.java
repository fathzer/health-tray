package com.fathzer.healthtray;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fathzer.healthtray.CheckTask.Status;
import com.fathzer.healthtray.CheckTask.TaskResult;
import com.fathzer.healthtray.tasks.FreshnessCheckTask;
import com.fathzer.healthtray.tasks.TimestampSupplier;

/** Tests for {@link FreshnessCheckTask}.
 * <BR>Verifies the three scenarios:
 * <ul>
 *   <li>Source unavailable (supplier throws {@link IOException}) &rarr; ERROR</li>
 *   <li>Source fresher than maxAge &rarr; OK</li>
 *   <li>Source older than maxAge &rarr; ERROR</li>
 * </ul>
 */
class FreshnessCheckTaskTest {

	@Test
	void sourceUnavailable_returnsError() {
		TimestampSupplier supplier = () -> { throw new IOException("file not found"); };
		FreshnessCheckTask task = new FreshnessCheckTask("Test", supplier, Duration.ofHours(1), 60);

		TaskResult result = task.run();

		assertEquals(Status.ERROR, result.type());
		assertEquals("Source unavailable", result.message());
	}

	@Test
	void sourceFresh_returnsOk() {
		Instant recent = Instant.now().minus(Duration.ofMinutes(5));
		TimestampSupplier supplier = () -> recent;
		FreshnessCheckTask task = new FreshnessCheckTask("Test", supplier, Duration.ofHours(1), 60);

		TaskResult result = task.run();

		assertEquals(Status.OK, result.type());
		assertTrue(result.message().startsWith("Fresh:"), "Expected message to start with 'Fresh:', got: " + result.message());
	}

	@Test
	void sourceStale_returnsError() {
		Instant old = Instant.now().minus(Duration.ofHours(2));
		TimestampSupplier supplier = () -> old;
		FreshnessCheckTask task = new FreshnessCheckTask("Test", supplier, Duration.ofHours(1), 60);

		TaskResult result = task.run();

		assertEquals(Status.ERROR, result.type());
		assertTrue(result.message().startsWith("Stale:"), "Expected message to start with 'Stale:', got: " + result.message());
	}

	@Test
	void sourceSlightlyUnderMaxAge_returnsOk() {
		// age just under maxAge should be OK.
		Instant recent = Instant.now().minus(Duration.ofSeconds(59));
		TimestampSupplier supplier = () -> recent;
		FreshnessCheckTask task = new FreshnessCheckTask("Test", supplier, Duration.ofSeconds(60), 60);

		TaskResult result = task.run();

		assertEquals(Status.OK, result.type());
	}
}
