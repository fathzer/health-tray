package com.fathzer.healthtray;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Adds drag-to-move support to an AWT {@link Window}.
 * <BR>Once attached, the user can drag the window by clicking and holding anywhere on it,
 * then moving the mouse. This is useful for notifications that have no title bar.
 */
final class Dragger {
	private Dragger() {
		// To prevent instantiation
	}

	/** Attaches a mouse listener to the given window that allows the user to drag it around the screen.
	 * @param window the window to make draggable.
	 */
	static void attach(Window window) {
		window.addMouseMotionListener(new MouseAdapter() {
			private Point dragOrigin = null;
			private Point windowOrigin = null;

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
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				dragOrigin = null;
			}
		});
	}
}
