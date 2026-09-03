package com.fathzer.healthtray;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Window displaying the current state of every {@link CheckTask}.
 * <BR>The window subscribes to each task's {@link CheckTask.Listener#onCheckDone} event to refresh
 * the table in real-time (only while visible). Callers invoke {@link #show()} (on the EDT) to display it.
 */
public class StatusWindow implements CheckTask.Listener {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final String[] COLUMNS = { "Name", "State", "Message", "Period", "Last check", "Last change" };

	private final List<CheckTask> tasks;
	private final JFrame frame;
	private final DefaultTableModel model;

	/** Creates a status window bound to the given tasks.
	 * <BR>Automatically subscribes to each task's check-done events.
	 * @param tasks the tasks to display.
	 * @param restoreAction action invoked when the user clicks the "Restore" button.
	 * @param quitAction action invoked when the user clicks the "Quit" button.
	 */
	public StatusWindow(List<CheckTask> tasks, Runnable restoreAction, Runnable quitAction) {
		this.tasks = tasks;
		this.model = new DefaultTableModel(COLUMNS, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		JTable table = new JTable(model);
		table.setRowHeight(22);
		table.getColumnModel().getColumn(1).setCellRenderer(new StatusCellRenderer());
		table.getColumnModel().getColumn(3).setCellRenderer(new PeriodCellRenderer());
		table.getColumnModel().getColumn(4).setCellRenderer(new TimeCellRenderer());
		table.getColumnModel().getColumn(5).setCellRenderer(new TimeCellRenderer());

		JButton restoreButton = new JButton("Restore alerts");
		restoreButton.addActionListener(e -> restoreAction.run());
		JButton quitButton = new JButton("Quit");
		quitButton.addActionListener(e -> quitAction.run());

		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
		bottom.add(restoreButton);
		bottom.add(Box.createHorizontalGlue());
		bottom.add(quitButton);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		content.add(new JScrollPane(table), BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);

		this.frame = new JFrame("HealthTray - Status");
		frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		frame.setContentPane(content);
		frame.setSize(820, 240);
		frame.setLocationRelativeTo(null);

		for (CheckTask task : tasks) {
			task.addListener(this);
		}
	}

	/** Refreshes the table from the tasks and makes the window visible. Must be called on the EDT. */
	public void show() {
		refresh();
		frame.setState(JFrame.NORMAL);
		frame.setVisible(true);
		frame.toFront();
		frame.requestFocus();
	}

	/** Re-reads the tasks state and rebuilds the table rows. */
	private void refresh() {
		model.setRowCount(0);
		for (CheckTask task : tasks) {
			model.addRow(new Object[] {
					task.getName(),
					task.getStatus(),
					task.getMessage() == null ? "" : task.getMessage(),
					task.getPeriod(),
					task.getLastCheck(),
					task.getLastChange()
			});
		}
	}

	@Override
	public void onCheckDone(CheckTask task) {
		// Only refresh if the window is currently visible, to avoid unnecessary EDT work.
		if (frame.isVisible()) {
			SwingUtilities.invokeLater(this::refresh);
		}
	}

	/** Renders a {@link CheckTask.Status} as colored text. */
	private static final class StatusCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			if (value instanceof CheckTask.Status status) {
				label.setForeground(switch (status) {
					case OK -> new Color(0x2E, 0x7D, 0x32);
					case ERROR -> new Color(0xC6, 0x28, 0x28);
				});
				label.setText(status.name());
			} else {
				label.setText("?");
			}
			return label;
		}
	}

	/** Renders a period in seconds as {@code Xd Xh Xm Xs} (only non-zero parts are shown). */
	private static final class PeriodCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			if (value instanceof Long period) {
				label.setText(formatPeriod(period));
			} else {
				label.setText("-");
			}
			return label;
		}

		private static String formatPeriod(long seconds) {
			long days = seconds / 86_400;
			long hours = (seconds % 86_400) / 3_600;
			long minutes = (seconds % 3_600) / 60;
			long secs = seconds % 60;
			StringBuilder sb = new StringBuilder();
			if (days > 0) sb.append(days).append("d ");
			if (hours > 0 || days > 0) sb.append(hours).append("h ");
			if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
			sb.append(secs).append("s");
			return sb.toString().strip();
		}
	}

	/** Renders an {@link Instant} as {@code HH:mm:ss} in the local time zone. */
	private static final class TimeCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			if (value instanceof Instant instant) {
				label.setText(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime().format(TIME_FORMAT));
			} else {
				label.setText("-");
			}
			return label;
		}
	}

	/** Convenience wrapper to ensure {@link #show()} runs on the EDT. */
	public void showOnEdt() {
		if (SwingUtilities.isEventDispatchThread()) {
			show();
		} else {
			SwingUtilities.invokeLater(this::show);
		}
	}
}
