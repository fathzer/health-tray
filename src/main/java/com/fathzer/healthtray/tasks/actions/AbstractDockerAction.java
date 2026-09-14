package com.fathzer.healthtray.tasks.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fathzer.healthtray.AbstractCheckTask.TaskResult;

/** An {@link Action} that runs a verification inside a temporary Docker container.
 * <BR>The container is started with the configured image and environment variables, then the
 * action waits for the container to become ready (via {@link #waitForReady()}), and finally
 * performs the verification (via {@link #verify()}).
 * <BR>The container is always destroyed after the action runs, regardless of success or failure.
 * <BR>Subclasses must implement:
 * <ul>
 *   <li>{@link #getEnv()} to provide the environment variables for the container,</li>
 *   <li>{@link #waitForReady()} to wait until the container's service is available,</li>
 *   <li>{@link #verify()} to perform the actual verification and return a {@link TaskResult}.</li>
 * </ul>
 */
public abstract class AbstractDockerAction implements Action {
	private static final Logger LOGGER = Logger.getLogger(AbstractDockerAction.class.getName());
	private static final String DOCKER = locateDocker();
	private static final long STARTUP_TIMEOUT_SECONDS = 90;
	private static final long STARTUP_POLL_INTERVAL_MS = 1000;

	private final String containerName;
	private final String image;

	/** Creates a {@link AbstractDockerAction}.
	 * @param image the Docker image to use for the temporary container.
	 */
	protected AbstractDockerAction(String image) {
		this.image = image;
		this.containerName = "hosting-health-" + UUID.randomUUID();
	}

	/** Returns the environment variables to pass to the container.
	 * <BR>Each entry must be in the {@code KEY=VALUE} format.
	 * @return the environment variables (empty by default).
	 */
	protected List<String> getEnv() {
		return List.of();
	}

	/** Waits until the container's service is ready.
	 * <BR>Implementations may use {@link #pollUntilReady(String[])} to poll a readiness command.
	 * @throws IOException if the container does not become ready.
	 * @throws InterruptedException if the thread is interrupted while waiting. */
	protected abstract void waitForReady() throws IOException, InterruptedException;

	/** Performs the verification inside the ready container and returns the result.
	 * @return the {@link TaskResult} describing the outcome of the verification.
	 * @throws IOException if the verification fails unexpectedly.
	 * @throws InterruptedException if the thread is interrupted. */
	protected abstract TaskResult verify() throws IOException, InterruptedException;

	@Override
	public final TaskResult run() throws IOException {
		try {
			startContainer();
			waitForReady();
			return verify();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted during Docker action", e);
		} finally {
			destroyContainer();
		}
	}

	/** Returns the container name, for use by subclasses when running commands inside it.
	 * @return the container name. */
	protected String getContainerName() {
		return containerName;
	}

	/** Returns the Docker executable path.
	 * @return the Docker executable path. */
	protected String getDocker() {
		return DOCKER;
	}

	/** Polls a readiness command inside the container until it returns exit code 0 or the
	 * startup timeout elapses.
	 * @param pingCommand the command to run inside the container (without the {@code docker exec}
	 *        prefix, which is added automatically).
	 * @throws IOException if the container does not become ready within the startup timeout.
	 * @throws InterruptedException if the thread is interrupted while waiting. */
	protected void pollUntilReady(String[] pingCommand) throws IOException, InterruptedException {
		pollUntilReady(pingCommand, List.of());
	}

	/** Polls a readiness command inside the container until it returns exit code 0 or the
	 * startup timeout elapses.
	 * @param pingCommand the command to run inside the container (without the {@code docker exec}
	 *        prefix, which is added automatically).
	 * @param execEnv environment variables to pass to the {@code docker exec} command
	 *        (each entry in {@code KEY=VALUE} format, may be empty).
	 * @throws IOException if the container does not become ready within the startup timeout.
	 * @throws InterruptedException if the thread is interrupted while waiting. */
	protected void pollUntilReady(String[] pingCommand, List<String> execEnv) throws IOException, InterruptedException {
		Instant deadline = Instant.now().plusSeconds(STARTUP_TIMEOUT_SECONDS);
		while (Instant.now().isBefore(deadline)) {
			try {
				String[] fullCommand = new String[pingCommand.length + 3 + execEnv.size() * 2];
				int i = 0;
				fullCommand[i++] = DOCKER;
				fullCommand[i++] = "exec";
				for (String env : execEnv) {
					fullCommand[i++] = "-e";
					fullCommand[i++] = env;
				}
				fullCommand[i++] = containerName;
				System.arraycopy(pingCommand, 0, fullCommand, i, pingCommand.length);
				int exit = runCommand(fullCommand, false);
				if (exit == 0) {
					return;
				}
			} catch (IOException e) {
				// Service is not ready yet, retry after the poll interval.
			}
			Thread.sleep(STARTUP_POLL_INTERVAL_MS);
		}
		throw new IOException("Container did not become ready within " + STARTUP_TIMEOUT_SECONDS + " seconds");
	}

	/** Runs a command and returns its exit code.
	 * @param command the command to run.
	 * @param inheritIO whether to inherit the process's I/O.
	 * @return the exit code.
	 * @throws IOException if the command cannot be started.
	 * @throws InterruptedException if the thread is interrupted while waiting. */
	protected int runCommand(String[] command, boolean inheritIO) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(command);
		if (inheritIO) {
			pb.inheritIO();
		} else {
			// Discard stdout/stderr to avoid blocking the process if the pipe buffer fills up.
			pb.redirectErrorStream(true);
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		}
		Process process = pb.start();
		return process.waitFor();
	}

	private void startContainer() throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(DOCKER);
		command.add("run");
		command.add("-d");
		command.add("--name");
		command.add(containerName);
		for (String env : getEnv()) {
			command.add("-e");
			command.add(env);
		}
		command.add(image);
		runCommand(command.toArray(new String[0]), true);
		LOGGER.info(() -> "Started container " + containerName);
	}

	private void destroyContainer() {
		try {
			runCommand(new String[]{DOCKER, "rm", "-f", containerName}, false);
			LOGGER.info(() -> "Removed container " + containerName);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, e, () -> "Failed to remove container " + containerName);
			// Best-effort cleanup: ignore failures during shutdown.
		}
	}

	private static String locateDocker() {
		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			String separator = System.getProperty("path.separator");
			for (String dir : pathEnv.split(separator)) {
				Path candidate = Path.of(dir, "docker");
				if (Files.isExecutable(candidate)) {
					return candidate.toString();
				}
			}
		}
		return "docker";
	}
}
