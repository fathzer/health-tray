package com.fathzer.healthtray;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Frame;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EventObject;
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
import javax.swing.AbstractCellEditor;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

import com.fathzer.soft.ajlib.swing.table.RowSorter;

/** Window displaying the current state of every {@link AbstractCheckTask}.
 * <BR>The window subscribes to each task's {@link AbstractCheckTask.Listener#onCheckDone} event to refresh
 * the table in real-time (only while visible). Callers invoke {@link #show()} (on the EDT) to display it.
 */
class StatusWindow implements AbstractCheckTask.Listener {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	/** Sample date string used to compute the column width for date columns (worst case). */
	private static final String DATE_SAMPLE = "2099-12-31 23:59:59";
	/** Pale color used for labels of paused tasks. */
	private static final Color PAUSED_COLOR = new Color(0x99, 0x99, 0x99);
	private static final String[] COLUMNS = { "", "Name", "State", "Message", "Period", "Last check", "Last change" };
	private static final int PAUSE_COL = 0;
	private static final int NAME_COL = 1;
	private static final int STATE_COL = 2;
	private static final int MESSAGE_COL = 3;
	private static final int PERIOD_COL = 4;
	private static final int LAST_CHECK_COL = 5;
	private static final int LAST_CHANGE_COL = 6;

	private final List<AbstractCheckTask> tasks;
	private final JFrame frame;
	private final DefaultTableModel model;

	/** Creates a status window bound to the given tasks.
	 * <BR>Automatically subscribes to each task's check-done events.
	 * @param tasks the tasks to display.
	 * @param restoreAction action invoked when the user clicks the "Restore" button.
	 * @param quitAction action invoked when the user clicks the "Quit" button.
	 */
	public StatusWindow(List<AbstractCheckTask> tasks, Runnable restoreAction, Runnable quitAction) {
		this.tasks = tasks;
		this.model = new DefaultTableModel(COLUMNS, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return column == PAUSE_COL;
			}
		};
		JTable table = new JTable(model);
		table.setRowHeight(22);
		// Pause/start button column.
		table.getColumnModel().getColumn(PAUSE_COL).setCellRenderer(new PauseButtonRenderer());
		table.getColumnModel().getColumn(PAUSE_COL).setCellEditor(new PauseButtonEditor());
		table.getColumnModel().getColumn(NAME_COL).setCellRenderer(new PausedAwareCellRenderer());
		table.getColumnModel().getColumn(STATE_COL).setCellRenderer(new StatusCellRenderer());
		table.getColumnModel().getColumn(MESSAGE_COL).setCellRenderer(new PausedAwareCellRenderer());
		table.getColumnModel().getColumn(PERIOD_COL).setCellRenderer(new PeriodCellRenderer());
		table.getColumnModel().getColumn(LAST_CHECK_COL).setCellRenderer(new TimeCellRenderer());
		table.getColumnModel().getColumn(LAST_CHANGE_COL).setCellRenderer(new TimeCellRenderer());
		fixColumnWidths(table);
		// Enable sorting by clicking on column headers (ASCENDING -> DESCENDING -> UNSORTED cycle).
		RowSorter<DefaultTableModel> sorter = new RowSorter<>(model);
		// Pause column: sort by paused state (running tasks first, paused last).
		sorter.setComparator(PAUSE_COL, (o1, o2) -> {
			if (o1 instanceof AbstractCheckTask t1 && o2 instanceof AbstractCheckTask t2) {
				return Boolean.compare(t1.isPaused() || !t1.isInited(), t2.isPaused() || !t2.isInited());
			}
			return 0;
		});
		sorter.setComparator(NAME_COL, Comparator.comparing(String::toString));
		sorter.setComparator(STATE_COL, Comparator.comparing(o -> o == null ? "" : o.toString()));
		sorter.setComparator(MESSAGE_COL, Comparator.comparing(o -> o == null ? "" : o.toString()));
		sorter.setComparator(PERIOD_COL, Comparator.comparing(o -> (Long) o));
		sorter.setComparator(LAST_CHECK_COL, Comparator.nullsLast(Comparator.naturalOrder()));
		sorter.setComparator(LAST_CHANGE_COL, Comparator.nullsLast(Comparator.naturalOrder()));
		table.setRowSorter(sorter);

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

		for (AbstractCheckTask task : tasks) {
			task.addListener(this);
		}
	}

	/** Fixes the width of the State, Period, Last check and Last change columns to fit their content.
	 * <BR>The date columns are sized to fit the worst-case date string (see {@link #DATE_SAMPLE}),
	 * so the width is stable whether or not dates are currently displayed. */
	private static void fixColumnWidths(JTable table) {
		Font font = table.getFont();
		java.awt.FontMetrics fm = table.getFontMetrics(font);
		// Pause column: fixed small width for the button.
		setFixedWidth(table, PAUSE_COL, 40);
		// State column: fit "ERROR" (the longest status) plus padding.
		int stateWidth = fm.stringWidth("ERROR") + 20;
		setFixedWidth(table, STATE_COL, stateWidth);
		// Period column: fit the longest period string we might produce (e.g. "999d").
		int periodWidth = fm.stringWidth("999d") + 20;
		setFixedWidth(table, PERIOD_COL, periodWidth);
		// Last check and Last change columns: fit the worst-case date string.
		int dateWidth = fm.stringWidth(DATE_SAMPLE) + 20;
		setFixedWidth(table, LAST_CHECK_COL, dateWidth);
		setFixedWidth(table, LAST_CHANGE_COL, dateWidth);
	}

	/** Sets a column to a fixed width (min = max = preferred). */
	private static void setFixedWidth(JTable table, int column, int width) {
		table.getColumnModel().getColumn(column).setMinWidth(width);
		table.getColumnModel().getColumn(column).setMaxWidth(width);
		table.getColumnModel().getColumn(column).setPreferredWidth(width);
	}

	/** Refreshes the table from the tasks and makes the window visible. Must be called on the EDT. */
	public void show() {
		refresh();
		frame.setState(Frame.NORMAL);
		frame.setVisible(true);
		frame.toFront();
		frame.requestFocus();
	}

	/** Re-reads the tasks state and rebuilds the table rows. */
	private void refresh() {
		model.setRowCount(0);
		for (AbstractCheckTask task : tasks) {
			model.addRow(new Object[] {
					task,
					task.getName(),
					task.getStatus(),
					task.getMessage() == null ? "" : task.getMessage(),
					task.getPeriod(),
					task.getLastCheck(),
					task.getLastChange()
			});
		}
	}

	/** Gets the task associated with a view row.
	 * @param table the table.
	 * @param viewRow the view row index.
	 * @return the task at that row, or {@code null} if not available. */
	private static AbstractCheckTask getTaskAt(JTable table, int viewRow) {
		int modelRow = table.convertRowIndexToModel(viewRow);
		Object value = table.getModel().getValueAt(modelRow, PAUSE_COL);
		return value instanceof AbstractCheckTask task ? task : null;
	}

	@Override
	public void onCheckDone(AbstractCheckTask task) {
		// Only refresh if the window is currently visible, to avoid unnecessary EDT work.
		if (frame.isVisible()) {
			SwingUtilities.invokeLater(this::refresh);
		}
	}

	@Override
	public void onStateChange(AbstractCheckTask task, AbstractCheckTask.Status oldStatus, AbstractCheckTask.Status newStatus) {
		onActivationChanged(task);
	}

	@Override
	public void onActivationChanged(AbstractCheckTask task) {
		if (frame.isVisible()) {
			SwingUtilities.invokeLater(this::refresh);
		}
	}

	/** Renders a text cell with a pale color when the task in that row is paused. */
	@SuppressWarnings("serial")
	private static final class PausedAwareCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			AbstractCheckTask task = getTaskAt(table, row);
			if (task != null && task.isPaused()) {
				label.setForeground(PAUSED_COLOR);
			} else {
				label.setForeground(table.getForeground());
			}
			return label;
		}
	}

	/** Renders a {@link AbstractCheckTask.Status} as colored text. */
	@SuppressWarnings("serial")
	private static final class StatusCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			AbstractCheckTask task = getTaskAt(table, row);
			if (value instanceof AbstractCheckTask.Status status) {
				label.setForeground(task != null && task.isPaused() ? PAUSED_COLOR : switch (status) {
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
	@SuppressWarnings("serial")
	private static final class PeriodCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			AbstractCheckTask task = getTaskAt(table, row);
			if (task != null && task.isPaused()) {
				label.setForeground(PAUSED_COLOR);
			} else {
				label.setForeground(table.getForeground());
			}
			if (value instanceof Long period) {
				label.setText(formatPeriod(period));
			} else {
				label.setText("-");
			}
			return label;
		}

		private static String formatPeriod(long seconds) {
			if (seconds >= 86_400) return (seconds / 86_400) + "d";
			if (seconds >= 3_600) return (seconds / 3_600) + "h";
			if (seconds >= 60) return (seconds / 60) + "mn";
			return seconds + "s";
		}
	}

	/** Renders an {@link Instant} as {@code HH:mm:ss} in the local time zone, or
	 * {@code yyyy-MM-dd HH:mm:ss} when the date differs from today. */
	@SuppressWarnings("serial")
	private static final class TimeCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			AbstractCheckTask task = getTaskAt(table, row);
			if (task != null && task.isPaused()) {
				label.setForeground(PAUSED_COLOR);
			} else {
				label.setForeground(table.getForeground());
			}
			if (value instanceof Instant instant) {
				LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
				LocalDate today = LocalDate.now(ZoneId.systemDefault());
				if (ldt.toLocalDate().equals(today)) {
					label.setText(ldt.toLocalTime().format(TIME_FORMAT));
				} else {
					label.setText(ldt.format(DATETIME_FORMAT));
				}
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

	/** Renders the pause/start button in the first column. */
	@SuppressWarnings("serial")
	private static final class PauseButtonRenderer extends JButton implements TableCellRenderer {
		public PauseButtonRenderer() {
			setHorizontalAlignment(SwingConstants.CENTER);
			setBorderPainted(false);
			setContentAreaFilled(false);
			setFocusable(false);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			if (value instanceof AbstractCheckTask task) {
				setText(task.isPaused() || !task.isInited() ? "▶" : "⏸");
				setToolTipText(task.isPaused() || !task.isInited() ? "Task paused, click to start" : "Task running, click to pause");
			} else {
				setText("");
			}
			return this;
		}
	}

	/** Editor for the pause/start button. Toggles the task's paused state and notifies {@link HealthTray}. */
	@SuppressWarnings({"serial","java:S1948"})
	private final class PauseButtonEditor extends AbstractCellEditor implements TableCellEditor {
		private final JButton button;
		private AbstractCheckTask currentTask;

		public PauseButtonEditor() {
			this.button = new JButton();
			button.setHorizontalAlignment(SwingConstants.CENTER);
			button.setBorderPainted(false);
			button.setContentAreaFilled(false);
			button.setFocusable(false);
			button.addActionListener(e -> toggleTask());
		}

		@Override
		public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
			if (value instanceof AbstractCheckTask task) {
				currentTask = task;
				button.setText(task.isPaused() || !task.isInited() ? "▶" : "⏸");
			}
			return button;
		}

		private void toggleTask() {
			if (currentTask == null) return;
			if (currentTask.isPaused() || !currentTask.isInited()) {
				HealthTray.resumeTask(currentTask);
			} else {
				HealthTray.pauseTask(currentTask);
			}
			fireEditingStopped();
			refresh();
		}

		@Override
		public Object getCellEditorValue() {
			return currentTask;
		}

		@Override
		public boolean isCellEditable(EventObject e) {
			return true;
		}

		@Override
		public boolean shouldSelectCell(EventObject e) {
			return false;
		}

		@Override
		public boolean stopCellEditing() {
			fireEditingStopped();
			return true;
		}

		@Override
		public void cancelCellEditing() {
			fireEditingCanceled();
		}
	}
}
