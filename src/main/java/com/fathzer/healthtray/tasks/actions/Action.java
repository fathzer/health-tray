package com.fathzer.healthtray.tasks.actions;

import java.io.IOException;

import com.fathzer.healthtray.AbstractCheckTask.TaskResult;

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