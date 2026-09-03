package com.fathzer.healthtray;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dorkbox.notify.Notify;
import dorkbox.notify.Position;
import dorkbox.notify.Theme;
import kotlin.Unit;

/** A reusable system-tray application that monitors the health of {@link CheckTask}s.
 * <BR>Displays a small heart icon in the system tray. Clicking the icon (or a notification) opens
 * a window showing the current state of every task.
 * <BR>On startup, a notification is shown, then each task's {@link CheckTask#init() init()} runs
 * immediately and {@link CheckTask#run() run()} is executed every {@link CheckTask#getPeriod() period}
 * seconds. When a task transitions to {@link CheckTask.Status#ERROR}, a persistent notification is
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

	private static ScheduledExecutorService scheduler;
	private static NotificationManager notificationManager;
	private static List<CheckTask> tasks;
	private static StatePersistence persistence;

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
	public static void launch(List<CheckTask> taskList) {
		launch(taskList, DEFAULT_STATE_FILE);
	}

	/** Launches the tray application with the given tasks and a custom state file.
	 * @param taskList the tasks to monitor.
	 * @param stateFile the file used to persist/restore task state across restarts.
	 */
	public static void launch(List<CheckTask> taskList, Path stateFile) {
		SwingUtilities.invokeLater(() -> start(taskList, stateFile));
	}

	private static void start(List<CheckTask> originalTasks, Path stateFile) {
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
		if (!SystemTray.isSupported()) {
			LOGGER.severe("System tray is not supported on this platform");
			return;
		}

		persistence = new StatePersistence(stateFile);

		// Check for duplicate task names.
		List<String> duplicates = findDuplicateNames(originalTasks);
		boolean hasDuplicates = !duplicates.isEmpty();
		if (hasDuplicates) {
			LOGGER.severe("Duplicate task names detected: " + duplicates + ". No checks will be loaded.");
		}
		final List<CheckTask> activeTasks = hasDuplicates ? List.of() : originalTasks;
		HealthTray.tasks = activeTasks;

		notificationManager = new NotificationManager(activeTasks);
		StatusWindow statusWindow = new StatusWindow(activeTasks, notificationManager::restore, HealthTray::quit);
		notificationManager.setOnNotificationClick(statusWindow::showOnEdt);

		SystemTray tray = SystemTray.getSystemTray();
		BufferedImage heart = TrayIconManager.loadHeart();
		Image initialIcon = hasDuplicates
				? (heart == null ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) : TrayIconManager.tint(heart, 0x88, 0x88, 0x88))
				: new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		TrayIcon icon = new TrayIcon(initialIcon, hasDuplicates ? "HealthTray - ERROR" : "HealthTray");
		icon.setImageAutoSize(true);
		// Single left-click (and double-click) on the tray icon opens the status window.
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
		if (!hasDuplicates) {
			new TrayIconManager(icon, activeTasks);
		}
		try {
			tray.add(icon);
		} catch (AWTException e) {
			LOGGER.log(Level.SEVERE, "Unable to add icon to system tray", e);
			return;
		}
		if (hasDuplicates) {
			String message = "Duplicate task names: " + String.join(", ", duplicates) + ". No checks loaded.";
			Notify notify = Notify.Companion.create()
					.title("HealthTray - Configuration error")
					.text(message)
					.theme(Theme.Companion.getDefaultDark())
					.position(Position.BOTTOM_RIGHT)
					.onClickAction(n -> {
						statusWindow.showOnEdt();
						return Unit.INSTANCE;
					});
			if (heart != null) {
				notify.image(TrayIconManager.tint(heart, 0x88, 0x88, 0x88));
			}
			notify.showError();
		} else {
			notify("HealthTray", "Surveillance activée", CheckTask.Status.OK, statusWindow::showOnEdt);
			scheduleChecks(activeTasks);
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

	/** @return the list of task names that appear more than once, or an empty list if all names are unique. */
	private static List<String> findDuplicateNames(List<CheckTask> tasks) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new HashSet<>();
		for (CheckTask task : tasks) {
			if (!seen.add(task.getName())) {
				duplicates.add(task.getName());
			}
		}
		return List.copyOf(duplicates);
	}

	private static void scheduleChecks(List<CheckTask> tasks) {
		// Load saved state (if any) before running init.
		Map<String, CheckTask.SavedState> savedStates = new HashMap<>();
		try {
			savedStates = persistence.load();
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Unable to load saved state, starting fresh", e);
		}
		scheduler = Executors.newScheduledThreadPool(tasks.size(), r -> {
			Thread t = new Thread(r, "health-check");
			t.setDaemon(true);
			return t;
		});
		for (CheckTask task : tasks) {
			CheckTask.SavedState saved = savedStates.get(task.getName());
			if (saved != null) {
				task.initWithRestore(saved);
			} else {
				task.init();
			}
			long period = task.getPeriod();
			scheduler.scheduleAtFixedRate(task::run, period, period, TimeUnit.SECONDS);
		}
	}

	/** Shows a one-shot notification (used for the startup message). */
	private static void notify(String title, String message, CheckTask.Status status, Runnable onClick) {
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
		if (status == CheckTask.Status.ERROR) {
			notify.showError();
		} else {
			notify.showInformation();
		}
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
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
		System.exit(0);
	}
}
