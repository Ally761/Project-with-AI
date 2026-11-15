package gameagent.gui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

/** Small factory helpers so panels stay short. Components get names, which the tests use to find them. */
public final class Widgets {
    private Widgets() {}

    public static JButton button(String text, String name, ActionListener action) {
        JButton b = new JButton(text);
        b.setName(name);
        b.addActionListener(action);
        return b;
    }

    public static JTextField field(String name, int columns) {
        JTextField f = new JTextField(columns);
        f.setName(name);
        return f;
    }

    /** Runs the action when Enter is pressed in the field. */
    public static void onEnter(JTextField f, Runnable action) {
        f.addActionListener(e -> action.run());
    }

    public static JTextArea text(String name, int rows, boolean monospaced) {
        JTextArea a = new JTextArea(rows, 40);
        a.setName(name);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        if (monospaced) a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    public static JScrollPane scroll(Component c, String title) {
        JScrollPane s = new JScrollPane(c);
        if (title != null) s.setBorder(BorderFactory.createTitledBorder(title));
        return s;
    }

    public static JPanel flow(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : parts) p.add(c);
        return p;
    }

    public static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
    }

    public static JTable table(String name, DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setName(name);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.setPreferredScrollableViewportSize(new java.awt.Dimension(300, 110));
        t.setRowHeight(22);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    public static void clear(DefaultTableModel m) { m.setRowCount(0); }
}
