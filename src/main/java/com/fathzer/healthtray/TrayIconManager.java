package com.fathzer.healthtray;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

/** Manages an icon that reflects the overall health of all {@link CheckTask}s.
 * <BR>When all tasks are {@link CheckTask.Status#OK OK}, a green-tinted icon is displayed.
 * <BR>When at least one task is in {@link CheckTask.Status#ERROR ERROR}, a red-tinted icon is displayed.
 * <BR>The icon is swapped only when the overall state actually changes.
 * <BR>This can be used to update a {@link TrayIcon} (in tray mode) or any other icon display
 * (e.g. a dorkbox Notify notification in no-tray mode) via a {@link Consumer<Image>}.
 */
class TrayIconManager implements CheckTask.Listener {
	private static final String HEART_RESOURCE = "/com/fathzer/healthtray/heart.png";

	private final Consumer<Image> iconSetter;
	private final List<CheckTask> tasks;
	private final Image okIcon;
	private final Image errorIcon;
	private final Image greyIcon;
	private boolean anyError = false;

	/** Creates a tray icon manager for a {@link TrayIcon} and subscribes to the given tasks.
	 * @param trayIcon the system tray icon to update.
	 * @param tasks the tasks to monitor for state changes.
	 */
	public TrayIconManager(TrayIcon trayIcon, List<CheckTask> tasks) {
		this(trayIcon::setImage, tasks);
	}

	/** Creates an icon manager that updates the icon via the given {@link Consumer} and subscribes to the given tasks.
	 * @param iconSetter called with the new icon image whenever the overall state changes.
	 * @param tasks the tasks to monitor for state changes.
	 */
	public TrayIconManager(Consumer<Image> iconSetter, List<CheckTask> tasks) {
		this.iconSetter = iconSetter;
		this.tasks = tasks;
		BufferedImage heart = loadHeart();
		this.okIcon = tint(heart, 0x00, 0xE6, 0x76);
		this.errorIcon = tint(heart, 0xFF, 0x17, 0x49);
		this.greyIcon = tint(heart, 0x88, 0x88, 0x88);
		// Set the initial icon to grey (initializing state).
		iconSetter.accept(greyIcon);
		for (CheckTask task : tasks) {
			task.addListener(this);
		}
	}

	@Override
	public void onStateChange(CheckTask task, CheckTask.Status oldStatus, CheckTask.Status newStatus) {
		refreshIcon();
	}

	/** Recomputes the overall state from all tasks and updates the icon if it changed. */
	private void refreshIcon() {
		boolean wasError = anyError;
		anyError = tasks.stream().anyMatch(t -> t.getStatus() == CheckTask.Status.ERROR);
		if (anyError != wasError) {
			iconSetter.accept(anyError ? errorIcon : okIcon);
		}
	}

	/** Updates the icon to reflect the current state of all tasks.
	 * <BR>This should be called after all tasks have been initialized, to switch from the grey
	 * "initializing" icon to the actual green/red state.
	 */
	public void updateFromCurrentState() {
		anyError = tasks.stream().anyMatch(t -> t.getStatus() == CheckTask.Status.ERROR);
		iconSetter.accept(anyError ? errorIcon : okIcon);
	}

	/** Loads the heart icon resource as a {@link BufferedImage}. */
	static BufferedImage loadHeart() {
		try (InputStream in = TrayIconManager.class.getResourceAsStream(HEART_RESOURCE)) {
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
	static Image tint(BufferedImage src, int r, int g, int b) {
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
