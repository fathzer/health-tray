package com.fathzer.healthtray;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;

/** Adds drag-to-move support to an AWT {@link Window}.
 * <BR>Once attached, the user can drag the window by clicking and holding anywhere on it,
 * then moving the mouse. This is useful for notifications that have no title bar.
 * <BR>Some windows fire their click action on mouse <i>press</i> (e.g. dorkbox Notify), so a
 * drag would be mistaken for a click. {@link #onClick(Runnable)} solves this by deferring the
 * action to mouse release: it runs only if no drag happened between press and release.
 */
final class Dragger {
	private static final AtomicReference<Runnable> PENDING_CLICK = new AtomicReference<>();

	private Dragger() {
		// To prevent instantiation
	}

	/** Attaches mouse listeners to the given window that allow the user to drag it around the screen.
	 * @param window the window to make draggable.
	 */
	static void attach(Window window) {
		MouseAdapter handler = new MouseAdapter() {
			private Point dragOrigin = null;
			private Point windowOrigin = null;
			private boolean moved = false;

			@Override
			public void mouseDragged(MouseEvent e) {
				Point current = MouseInfo.getPointerInfo().getLocation();
				if (dragOrigin == null) {
					dragOrigin = current;
					windowOrigin = window.getLocation();
				}
				int dx = current.x - dragOrigin.x;
				int dy = current.y - dragOrigin.y;
				window.setLocation(windowOrigin.x + dx, windowOrigin.y + dy);
				moved = true;
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				dragOrigin = null;
			}

			@Override
			public void mousePressed(MouseEvent e) {
				moved = false;
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				Runnable click = PENDING_CLICK.getAndSet(null);
				if (click != null && !moved) {
					click.run();
				}
				dragOrigin = null;
				moved = false;
			}
		};
		window.addMouseMotionListener(handler);
		window.addMouseListener(handler);
	}

	/** Defers a click action until mouse release on the draggable window, unless a drag
	 * happens between press and release.
	 * <BR>Must be called while handling the mouse <i>press</i> that originated the click
	 * (e.g. from a notification's click callback). Only meaningful for windows made draggable
	 * with {@link #attach(Window)}.
	 * @param action the action to run on a click without drag.
	 */
	static void onClick(Runnable action) {
		PENDING_CLICK.set(action);
	}
}
