package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** The real window services: SwingWorker for background work, status bar, and message dialogs. */
public final class SwingContext implements GuiContext {
    private final JFrame frame;
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar busyBar = new JProgressBar();
    private final JPanel statusBar = new JPanel(new BorderLayout(8, 0));
    private int running;

    public SwingContext(JFrame frame) {
        this.frame = frame;
        busyBar.setIndeterminate(true);
        busyBar.setVisible(false);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(busyBar, BorderLayout.EAST);
    }

    public JComponent statusBar() { return statusBar; }

    @Override
    public <T> void run(String busy, Callable<T> work, Consumer<T> done, JComponent... lock) {
        for (JComponent c : lock) c.setEnabled(false);
        running++;
        busyBar.setVisible(true);
        statusLabel.setText(busy);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception { return work.call(); }

            @Override
            protected void done() {
                for (JComponent c : lock) c.setEnabled(true);
                if (--running == 0) busyBar.setVisible(false);
                if (statusLabel.getText().equals(busy)) statusLabel.setText(" ");
                try {
                    done.accept(get());
                } catch (ExecutionException e) {
                    error(Errors.describe(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    error(Errors.describe(e));
                }
            }
        }.execute();
    }

    @Override
    public void status(String text) {
        if (SwingUtilities.isEventDispatchThread()) statusLabel.setText(text);
        else SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    @Override
    public void error(String text) {
        statusLabel.setText("! " + text);
        JOptionPane.showMessageDialog(frame, text, "GameAgent", JOptionPane.WARNING_MESSAGE);
    }

    @Override
    public void info(String text) {
        JOptionPane.showMessageDialog(frame, text, "GameAgent", JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(frame, question, "GameAgent", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    @Override
    public void openLink(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            // fall through to showing the address
        }
        info("Open this address in your browser:\n" + url);
    }

    @Override
    public List<String> askAll(String title, List<String> questions) {
        JPanel form = new JPanel(new GridBagLayout());
        List<JTextArea> boxes = new ArrayList<>();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new java.awt.Insets(3, 0, 3, 0);
        int row = 0;
        for (String q : questions) {
            JTextArea box = new JTextArea(2, 44);
            box.setLineWrap(true);
            box.setWrapStyleWord(true);
            boxes.add(box);
            JTextArea label = new JTextArea(q);
            label.setEditable(false);
            label.setOpaque(false);
            label.setLineWrap(true);
            label.setWrapStyleWord(true);
            c.gridy = row++;
            form.add(label, c);
            c.gridy = row++;
            form.add(new JScrollPane(box), c);
        }
        int choice = JOptionPane.showConfirmDialog(frame, new JScrollPane(form), title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return null;
        List<String> answers = new ArrayList<>();
        for (JTextArea b : boxes) answers.add(b.getText().trim());
        return answers;
    }
}
