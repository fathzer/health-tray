package com.fathzer.healthtray;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Manages an icon that reflects the overall health of all {@link AbstractCheckTask}s.
 * <BR>The icon can be in one of three states:
 * <ul>
 *   <li><b>Grey</b> &ndash; no task is active (all are paused or not yet initialized).</li>
 *   <li><b>Green</b> &ndash; at least one task is active and all active tasks are {@link AbstractCheckTask.Status#OK OK}.</li>
 *   <li><b>Red</b> &ndash; at least one active task is in {@link AbstractCheckTask.Status#ERROR ERROR}.</li>
 * </ul>
 * <BR>The manager subscribes to each task's {@link AbstractCheckTask.Listener} events and updates
 * the icon automatically when the overall state changes. No external calls are needed after construction.
 * <BR>The icon is updated via a {@link Consumer<Image>}, which can be a {@link TrayIcon#setImage}
 * reference (in tray mode) or any other icon display mechanism (e.g. a dorkbox Notify notification
 * in no-tray mode).
 */
class IconManager implements AbstractCheckTask.Listener {
	private static final String HEART_RESOURCE = "/com/fathzer/healthtray/heart.png";

	private final Consumer<Image> iconSetter;
	private final List<AbstractCheckTask> tasks;
	private final Image okIcon;
	private final Image errorIcon;
	private final Image greyIcon;
	/** Current icon state: 0=grey, 1=green, 2=red. */
	private int currentState = 0;

	/** Creates an icon manager that updates the icon via the given {@link Consumer} and subscribes to the given tasks.
	 * <BR>The initial icon is set to grey (no task is active yet). As tasks are initialized, paused,
	 * or change status, the icon is updated automatically via the task events.
	 * @param iconSetter called with the new icon image whenever the overall state changes.
	 * @param tasks the tasks to monitor.
	 */
	public IconManager(Consumer<Image> iconSetter, List<AbstractCheckTask> tasks) {
		this.iconSetter = iconSetter;
		this.tasks = tasks;
		BufferedImage heart = loadHeart();
		this.okIcon = tint(heart, 0x00, 0xE6, 0x76);
		this.errorIcon = tint(heart, 0xFF, 0x17, 0x49);
		this.greyIcon = tint(heart, 0x88, 0x88, 0x88);
		// Set the initial icon to grey (no task is active yet).
		currentState = 0;
		iconSetter.accept(greyIcon);
		for (AbstractCheckTask task : tasks) {
			task.addListener(this);
		}
	}

	/** Returns the current icon image, reflecting the current state of all tasks.
	 * <BR>This is useful for notifications that need to display the same icon as the tray
	 * (e.g. a duplicate-name error notification, where no task is active and the icon is grey).
	 * @return the current icon image. */
	Image getIcon() {
		return switch (currentState) {
			case 1 -> okIcon;
			case 2 -> errorIcon;
			default -> greyIcon;
		};
	}

	@Override
	public void onStateChange(AbstractCheckTask task, AbstractCheckTask.Status oldStatus, AbstractCheckTask.Status newStatus) {
		refreshIcon();
	}

	@Override
	public void onActivationChanged(AbstractCheckTask task) {
		refreshIcon();
	}

	/** Recomputes the overall state from all active tasks and updates the icon if it changed. */
	private void refreshIcon() {
		int newState = computeState();
		if (newState != currentState) {
			currentState = newState;
			iconSetter.accept(getIcon());
		}
	}

	/** Computes the overall state from all tasks.
	 * <BR>A task in {@link AbstractCheckTask.Status#ERROR ERROR} is considered an error even if it is
	 * stopped or initializing — the goal is to not hide an uncorrected error just because the task
	 * is suspended.
	 * @return 0=grey (no running task), 1=green (at least one running task, no error at all),
	 *         2=red (at least one task in ERROR, running or not, as long as at least one task is running). */
	private int computeState() {
		boolean anyRunning = false;
		boolean anyError = false;
		for (AbstractCheckTask task : tasks) {
			if (task.getStatus() == AbstractCheckTask.Status.ERROR) {
				anyError = true;
			}
			if (task.getActivationState() == AbstractCheckTask.ActivationState.RUNNING) {
				anyRunning = true;
			}
		}
		if (!anyRunning) return 0;
		return anyError ? 2 : 1;
	}

	/** Loads the heart icon resource as a {@link BufferedImage}. */
	private static BufferedImage loadHeart() {
		try (InputStream in = IconManager.class.getResourceAsStream(HEART_RESOURCE)) {
			if (in == null) {
				throw new IOException("Heart icon resource not found: " + HEART_RESOURCE);
			}
			BufferedImage img = ImageIO.read(in);
			if (img == null) {
				throw new IOException("Failed to read heart icon from resource: " + HEART_RESOURCE);
			}
			if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
			BufferedImage converted = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = converted.createGraphics();
			g.drawImage(img, 0, 0, null);
			g.dispose();
			return converted;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Creates a flat-colored copy of the source image, preserving alpha but replacing all RGB
	 * with the given solid color. This produces vivid, saturated icons (no luminance-based dimming).
	 */
	private static Image tint(BufferedImage src, int r, int g, int b) {
		BufferedImage result = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int rgb = (r << 16) | (g << 8) | b;
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int argb = src.getRGB(x, y);
				int alpha = (argb >> 24) & 0xFF;
				if (alpha == 0) {
					result.setRGB(x, y, 0);
				} else {
					result.setRGB(x, y, (alpha << 24) | rgb);
				}
			}
		}
		return result;
	}
}
