package com.fathzer.healthtray;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dorkbox.notify.Notify;
import dorkbox.notify.Position;
import dorkbox.notify.Theme;
import kotlin.Unit;

/** A reusable system-tray application that monitors the health of {@link AbstractCheckTask}s.
 * <BR>Displays a small heart icon in the system tray. Clicking the icon (or a notification) opens
 * a window showing the current state of every task.
 * <BR>On desktop environments where the system tray is not available or buggy (notably GNOME Shell,
 * which does not respect tray icon transparency), the tray icon can be skipped entirely by setting
 * the {@code -Dhealthtray.noTray=true} system property. This is auto-detected on GNOME via the
 * {@code XDG_CURRENT_DESKTOP} environment variable. In that case, the startup notification becomes
 * persistent (no auto-close, no close button) and can be dragged — it becomes the primary way to
 * interact with the application. Clicking it opens the status window, which has a "Quit" button.
 * <BR>On startup, a notification is shown, then each task's {@link AbstractCheckTask#init() init()} runs
 * immediately and {@link AbstractCheckTask#run() run()} is executed every {@link AbstractCheckTask#getPeriod() period}
 * seconds. When a task transitions to {@link AbstractCheckTask.Status#ERROR}, a persistent notification is
 * shown (until recovery or manual close). When it recovers, a short "{@code <name> is ok}"
 * notification is displayed.
 * <BR>Task state (status, message, timestamps) is persisted to a file on shutdown and restored on
 * the next startup.
 * <BR>Usage: call {@link #launch(List)} from the EDT (or any thread; it will be marshalled to the EDT)
 * with the list of tasks to monitor.
 */
public class HealthTray {
	private static final Logger LOGGER = Logger.getLogger(HealthTray.class.getName());
	private static final int NOTIFICATION_DURATION_MS = 10_000;
	private static final Path DEFAULT_STATE_FILE = Path.of("health-state.properties");
	private static final String NO_TRAY_PROP = "healthtray.noTray";

	private static ScheduledExecutorService scheduler;
	private static NotificationManager notificationManager;
	private static List<AbstractCheckTask> tasks;
	private static StatePersistence persistence;
	private static Notify startupNotification;
	private static TrayIconManager iconManager;
	private static final java.util.Map<AbstractCheckTask, java.util.concurrent.ScheduledFuture<?>> scheduledFutures = new java.util.concurrent.ConcurrentHashMap<>();

	private HealthTray() {
		// To prevent instantiation
	}

	/** Launches the tray application with the given tasks and the default state file
	 * ({@code health-state.properties} in the working directory).
	 * <BR>This method must be called once; it sets up the tray icon, notification system, status
	 * window, and scheduler. It returns immediately; the application runs until {@link System#exit}
	 * is called (e.g. via the "Quit" button).
	 * @param taskList the tasks to monitor.
	 */
	public static void launch(List<AbstractCheckTask> taskList) {
		launch(taskList, DEFAULT_STATE_FILE);
	}

	/** Launches the tray application with the given tasks and a custom state file.
	 * @param taskList the tasks to monitor.
	 * @param stateFile the file used to persist/restore task state across restarts.
	 */
	public static void launch(List<AbstractCheckTask> taskList, Path stateFile) {
		SwingUtilities.invokeLater(() -> start(taskList, stateFile));
	}

	private static void start(List<AbstractCheckTask> originalTasks, Path stateFile) {
		setupLookAndFeel();
		if (!SystemTray.isSupported()) {
			LOGGER.severe("System tray is not supported on this platform");
			return;
		}

		boolean noTray = isNoTrayMode();
		if (noTray) {
			LOGGER.info("Tray icon disabled (no-tray mode). The startup notification will be persistent.");
		}

		persistence = new StatePersistence(stateFile);

		// Check for duplicate task names.
		List<String> duplicates = findDuplicateNames(originalTasks);
		boolean hasDuplicates = !duplicates.isEmpty();
		if (hasDuplicates) {
			LOGGER.severe(() -> "Duplicate task names detected: " + duplicates + ". No checks will be loaded.");
		}
		final List<AbstractCheckTask> activeTasks = hasDuplicates ? List.of() : originalTasks;
		HealthTray.tasks = activeTasks;

		notificationManager = new NotificationManager(activeTasks);
		StatusWindow statusWindow = new StatusWindow(activeTasks, notificationManager::restore, HealthTray::quit);
		notificationManager.setOnNotificationClick(statusWindow::showOnEdt);

		BufferedImage heart = TrayIconManager.loadHeart();
		TrayIcon icon = createTrayIcon(hasDuplicates, noTray, statusWindow, heart);
		if (icon == null && !noTray) {
			return; // Tray icon creation failed
		}

		// The TrayIconManager (if created) starts with a grey "initializing" icon.
		// It is created inside showStartupNotification, attached to the tray icon or the notification.
		if (hasDuplicates) {
			showDuplicateNotification(duplicates, noTray, statusWindow, heart);
		} else {
			TrayIconManager iconManager = showStartupNotification(noTray, activeTasks, statusWindow, icon);
			// Run init() for all tasks in parallel (off the EDT), then schedule periodic checks.
			initAndSchedule(activeTasks, iconManager);
		}
		// Ensure state is saved even on unexpected shutdown (Ctrl+C, etc.).
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (persistence != null && tasks != null) {
				try {
					persistence.save(tasks);
				} catch (IOException e) {
					LOGGER.log(Level.WARNING, "Unable to save state on shutdown", e);
				}
			}
		}, "state-save"));
	}

	/** Sets the Nimbus look and feel if available, otherwise falls back to the default.
	 * <br>Failure to set Nimbus is non-fatal and logged at {@code FINE} level.
	 */
	private static void setupLookAndFeel() {
		try {
			for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
				if ("Nimbus".equals(info.getName())) {
					UIManager.setLookAndFeel(info.getClassName());
					break;
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.FINE, "Nimbus look and feel unavailable, using default", e);
		}
	}

	/** Creates and registers the system tray icon, wiring up click handlers to open the status window.
	 * <br>Note: this method does not create a {@link TrayIconManager}; the caller is responsible for
	 * creating one (so it can later call {@link TrayIconManager#updateFromCurrentState()} after init).
	 * @param hasDuplicates whether duplicate task names were detected.
	 * @param noTray if {@code true}, the tray icon is skipped and this method returns {@code null}.
	 * @param statusWindow the status window to open when the icon is clicked.
	 * @param heart the base heart image used for the icon.
	 * @return the created {@link TrayIcon}, or {@code null} if {@code noTray} is {@code true} or the
	 *         icon could not be added to the system tray.
	 */
	private static TrayIcon createTrayIcon(boolean hasDuplicates, boolean noTray,
			StatusWindow statusWindow, BufferedImage heart) {
		if (noTray) {
			return null;
		}
		SystemTray tray = SystemTray.getSystemTray();
		Image initialIcon = hasDuplicates
				? TrayIconManager.tint(heart, 0x88, 0x88, 0x88)
				: new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		TrayIcon icon = new TrayIcon(initialIcon, hasDuplicates ? "HealthTray - ERROR" : "HealthTray");
		icon.setImageAutoSize(true);
		icon.addActionListener(e -> statusWindow.show());
		icon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0
						|| e.getButton() == MouseEvent.BUTTON1) {
					statusWindow.show();
				}
			}
		});
		try {
			tray.add(icon);
		} catch (AWTException e) {
			LOGGER.log(Level.SEVERE, "Unable to add icon to system tray", e);
			return null;
		}
		return icon;
	}

	/** Shows a notification alerting the user that duplicate task names were detected and no checks were loaded.
	 * <br>In no-tray mode, a compact persistent notification is used. Otherwise, a standard error
	 * notification is displayed. Clicking either notification opens the status window.
	 * @param duplicates the list of duplicate task names.
	 * @param noTray whether the tray icon is disabled (no-tray mode).
	 * @param statusWindow the status window to open when the notification is clicked.
	 * @param heart the base heart image, tinted grey for the error icon.
	 */
	private static void showDuplicateNotification(List<String> duplicates, boolean noTray,
			StatusWindow statusWindow, BufferedImage heart) {
		String message = "Duplicate task names: " + String.join(", ", duplicates) + ". No checks loaded.";
		if (noTray) {
			Notify notify = showCompactNotification("Health Tray Off", statusWindow::showOnEdt);
			notify.setImage(new ImageIcon(TrayIconManager.tint(heart, 0x88, 0x88, 0x88).getScaledInstance(20, 20, Image.SCALE_SMOOTH)));
		} else {
			Notify notify = Notify.Companion.create()
					.title("HealthTray - Configuration error")
					.text(message)
					.theme(Theme.Companion.getDefaultDark())
					.position(Position.BOTTOM_RIGHT)
					.onClickAction(n -> {
						statusWindow.showOnEdt();
						return Unit.INSTANCE;
					});
			notify.image(TrayIconManager.tint(heart, 0x88, 0x88, 0x88));
			notify.showError();
		}
	}

	/** Shows the startup notification when all tasks are valid and creates a {@link TrayIconManager}.
	 * <br>In no-tray mode, a compact persistent notification replaces the tray icon and a
	 * {@link TrayIconManager} is attached to it so its icon changes color with the overall state.
	 * Otherwise, a short one-shot "Surveillance activée" notification is displayed and the
	 * {@link TrayIconManager} is attached to the tray icon.
	 * @param noTray whether the tray icon is disabled (no-tray mode).
	 * @param activeTasks the tasks to monitor.
	 * @param statusWindow the status window to open when the notification is clicked.
	 * @param icon the tray icon (non-null in tray mode, null in no-tray mode).
	 * @return the created {@link TrayIconManager} (starting with a grey "initializing" icon).
	 */
	private static TrayIconManager showStartupNotification(boolean noTray, List<AbstractCheckTask> activeTasks,
			StatusWindow statusWindow, TrayIcon icon) {
		if (noTray) {
			Notify notify = showCompactNotification("Health Tray On", statusWindow::showOnEdt);
			startupNotification = notify;
			return new TrayIconManager(img -> notify.setImage(new ImageIcon(img.getScaledInstance(20, 20, Image.SCALE_SMOOTH))), activeTasks);
		} else {
			startupNotification = notify("HealthTray", "Surveillance activée", AbstractCheckTask.Status.OK, statusWindow::showOnEdt);
			return new TrayIconManager(icon, activeTasks);
		}
	}

	/** Determines whether the tray icon should be skipped entirely.
	 * <BR>This is controlled by the {@code healthtray.noTray} system property:
	 * <ul>
	 *   <li>{@code true} or {@code false} forces the corresponding mode.</li>
	 *   <li>If unset, GNOME Shell is auto-detected via the {@code XDG_CURRENT_DESKTOP}
	 *       environment variable (which contains {@code GNOME} on GNOME-based desktops).</li>
	 * </ul>
	 * @return {@code true} if the tray icon should not be displayed.
	 */
	private static boolean isNoTrayMode() {
		String prop = System.getProperty(NO_TRAY_PROP);
		if (prop != null) {
			return Boolean.parseBoolean(prop);
		}
		String xdg = System.getenv("XDG_CURRENT_DESKTOP");
		return xdg != null && xdg.toUpperCase().contains("GNOME");
	}

	/** Shows a compact, persistent, draggable notification (used in no-tray mode).
	 * <BR>The notification has no body text, no close button, and no initial icon (the icon is
	 * managed externally, typically by a {@link TrayIconManager} that updates it based on task state).
	 * It stays visible until the application exits. Clicking it runs the provided {@code onClick} action.
	 * @param title the title to display (e.g. "Health Tray On" or "Health Tray Off").
	 * @param onClick action invoked when the user clicks the notification.
	 * @return the created {@link Notify} instance, so callers can update its icon (e.g. via {@code setImage}).
	 */
	private static Notify showCompactNotification(String title, Runnable onClick) {
		int originalHeight = Notify.Companion.getHEIGHT();
		Notify.Companion.setHEIGHT(50);
		try {
			Notify notify = Notify.Companion.create()
					.title(title)
					.text("")
					.theme(Theme.Companion.getDefaultDark())
					.position(Position.BOTTOM_RIGHT)
					.hideCloseButton()
					.onClickAction(n -> {
						onClick.run();
						return Unit.INSTANCE;
					});
			notify.show();
			makeDraggable(notify);
			return notify;
		} finally {
			Notify.Companion.setHEIGHT(originalHeight);
		}
	}

	/** Makes a dorkbox {@link Notify} notification draggable by the user.
	 * <BR>This uses reflection to access the internal {@code JWindow} (DesktopNotify) and adds
	 * a mouse motion listener that allows the user to drag the notification around the screen.
	 * <BR>This must be called AFTER the notification has been shown (e.g. after {@code showInformation()},
	 * {@code showError()}, etc.), so that the internal popup window exists.
	 * @param notify the notification to make draggable.
	 */
	private static void makeDraggable(Notify notify) {
		try {
			Field popupField = Notify.class.getDeclaredField("notifyPopup");
			popupField.setAccessible(true);
			Object popup = popupField.get(notify);
			if (popup instanceof Window window) {
				Dragger.attach(window);
			}
		} catch (NoSuchFieldException | IllegalAccessException e) {
			LOGGER.log(Level.FINE, "Unable to make notification draggable (reflection failed)", e);
		}
	}

	/** @return the list of task names that appear more than once, or an empty list if all names are unique. */
	private static List<String> findDuplicateNames(List<AbstractCheckTask> tasks) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new HashSet<>();
		for (AbstractCheckTask task : tasks) {
			if (!seen.add(task.getName())) {
				duplicates.add(task.getName());
			}
		}
		return List.copyOf(duplicates);
	}

	/** Initializes all tasks in parallel (off the EDT), then switches the icon from grey to the
	 * actual green/red state and starts the periodic scheduling.
	 * <BR>The init phase runs on a separate thread pool so the EDT is not blocked. Once all inits
	 * complete, {@link TrayIconManager#updateFromCurrentState()} is called on the EDT to refresh the
	 * icon, and the periodic scheduler is started.
	 * @param tasks the tasks to initialize and schedule.
	 * @param iconManager the icon manager to update after init (may be null if no icon is managed).
	 */
	private static void initAndSchedule(List<AbstractCheckTask> tasks, TrayIconManager iconMgr) {
		HealthTray.iconManager = iconMgr;
		// Load saved state (if any) before running init.
		Map<String, AbstractCheckTask.SavedState> loadedStates;
		try {
			loadedStates = persistence.load();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Unable to load saved state, starting fresh", e);
			loadedStates = new HashMap<>();
		}
		final Map<String, AbstractCheckTask.SavedState> savedStates = loadedStates;
		// Run all inits in parallel (paused tasks are skipped by init()), then schedule on completion.
		CompletableFuture<Void> allInitResults = CompletableFuture.allOf(tasks.stream()
			.map(task -> CompletableFuture.runAsync(() -> task.init(savedStates.get(task.getName()))))
			.toArray(CompletableFuture[]::new));
		scheduler = Executors.newScheduledThreadPool(tasks.size(), r -> {
			Thread t = new Thread(r, "health-check");
			t.setDaemon(true);
			return t;
		});
		// All inits are done: create the scheduler, update the icon, then schedule.
		allInitResults.thenRun(() -> SwingUtilities.invokeLater(() -> {
			for (AbstractCheckTask task : tasks) {
				scheduleTask(task);
			}
			updateIcon();
		}));
	}

	/** Schedules a task for periodic execution if it is inited and not paused.
	 * <BR>Does nothing if the task is not inited or is paused.
	 * @param task the task to schedule.
	 */
	private static void scheduleTask(AbstractCheckTask task) {
		if (!task.isInited() || task.isPaused()) {
			return;
		}
		long period = task.getPeriod();
		long initialDelay = computeInitialDelay(task, period);
		ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> safeRun(task), initialDelay, period, TimeUnit.SECONDS);
		scheduledFutures.put(task, future);
	}

	/** Updates the tray icon to reflect the current state of all tasks. */
	private static void updateIcon() {
		if (iconManager != null && tasks.stream().anyMatch(AbstractCheckTask::isInited)) {
			iconManager.updateFromCurrentState();
		}
	}

	/** Pauses a task: cancels its scheduled execution and marks it as not inited.
	 * <BR>The task's state (status, message, lastCheck) is preserved so it can be restored on resume.
	 * <BR>This method must be called on the EDT.
	 * @param task the task to pause.
	 */
	static void pauseTask(AbstractCheckTask task) {
		task.setPaused(true);
		ScheduledFuture<?> future = scheduledFutures.remove(task);
		if (future != null) {
			future.cancel(false);
		}
		// Mark as not inited so the icon reflects the paused state.
		// Note: we don't fire onStateChange because the status itself doesn't change,
		// only the scheduling state.
		updateIcon();
	}

	/** Resumes a task: re-initializes it and schedules it if init succeeds.
	 * <BR>The initialization runs asynchronously (off the EDT). If it fails, the task
	 * remains paused and is not scheduled.
	 * <BR>This method must be called on the EDT.
	 * @param task the task to resume.
	 */
	static void resumeTask(AbstractCheckTask task) {
		task.setPaused(false);
		// Capture the current state so init() can restore it (preserving lastCheck, etc.).
		AbstractCheckTask.SavedState currentState = task.captureState();
		CompletableFuture.runAsync(() -> {
			// Re-init with the current state (paused=false in the captured state since we just set it).
			task.init(currentState);
			SwingUtilities.invokeLater(() -> {
				if (task.isInited()) {
					scheduleTask(task);
				}
				updateIcon();
			});
		});
	}

	/** Runs a task safely, catching and logging any exception so that the scheduled execution is not suppressed.
	 * @param task the task to run.
	 */
	private static void safeRun(AbstractCheckTask task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			LOGGER.log(Level.SEVERE, e, () -> "Error during run of task " + task.getName());
		}
	}

	/** Computes the initial delay before the first scheduled run of a task.
	 * <BR>If the task has a saved last check time, the delay is calculated so the next run occurs
	 * one period after the last check: {@code lastCheck + period - now}, clamped to 0 if negative
	 * (the task is already overdue and should run immediately).
	 * <BR>If the task has no saved state (first launch), the delay is 0 so the task runs immediately.
	 * @param task the task to schedule.
	 * @param period the task period in seconds.
	 * @return the initial delay in seconds (0 if the task should run immediately).
	 */
	private static long computeInitialDelay(AbstractCheckTask task, long period) {
		Instant lastCheck = task.getLastCheck();
		if (lastCheck == null) {
			return 0;
		}
		long elapsed = Instant.now().getEpochSecond() - lastCheck.getEpochSecond();
		long remaining = period - elapsed;
		return Math.max(0, remaining);
	}

	/** Shows a one-shot notification (used for the startup message).
	 * @return the created {@link Notify} instance, so callers can close it on quit. */
	private static Notify notify(String title, String message, AbstractCheckTask.Status status, Runnable onClick) {
		Notify notify = Notify.Companion.create()
				.title(title)
				.text(message)
				.theme(Theme.Companion.getDefaultDark())
				.position(Position.BOTTOM_RIGHT)
				.hideAfter(NOTIFICATION_DURATION_MS)
				.onClickAction(n -> {
					onClick.run();
					return Unit.INSTANCE;
				});
		if (status == AbstractCheckTask.Status.ERROR) {
			notify.showError();
		} else {
			notify.showInformation();
		}
		return notify;
	}

	/** Saves the current state, shuts down the scheduler and exits the application. */
	private static void quit() {
		if (persistence != null && tasks != null) {
			try {
				persistence.save(tasks);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Unable to save state", e);
			}
		}
		if (notificationManager != null) {
			notificationManager.closeAll();
		}
		if (startupNotification != null) {
			startupNotification.close();
		}
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
		System.exit(0);
	}
}
