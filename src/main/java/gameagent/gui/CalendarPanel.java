package gameagent.gui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import gameagent.agent.AgentController;
import gameagent.calendar.CalendarGame;
import gameagent.calendar.CalendarPlan;
import gameagent.calendar.ScheduleMode;

/** Plan when to play your games over a week or a month. The schedule is computed by rules; the AI reads wording and estimates lengths. */
public final class CalendarPanel extends FeaturePanel {
    private final JTextField availability = Widgets.field("availability", 28);
    private final JLabel slots = new JLabel(" ");
    private final DefaultTableModel gamesModel = Widgets.model("Game you added", "Hours");
    private final JTable gamesTable = Widgets.table("calendarGames", gamesModel);
    private final JTextField gameName = Widgets.field("calendarGameName", 16);
    private final JTextField gameHours = Widgets.field("calendarGameHours", 5);
    private final JComboBox<String> length = new JComboBox<>(new String[] {"1 week", "2 weeks", "3 weeks", "4 weeks (a month)"});
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"Focus: finish one game at a time", "Mix: rotate between games"});
    private final JTextField tweak = Widgets.field("tweak", 30);
    private final JTextArea plan = Widgets.text("plan", 18, true);
    private final JLabel stale = new JLabel(" ");
    private final JButton setButton, addButton, removeButton, make, applyTweak, resetTweaks;

    public CalendarPanel(AgentController agent, GuiContext ui) {
        super(agent, ui);
        length.setName("calendarLength");
        mode.setName("calendarMode");
        setButton = Widgets.button("Save availability", "setAvailability", e -> setAvailability());
        addButton = Widgets.button("Add game", "addGame", e -> addGame());
        removeButton = Widgets.button("Remove selected", "removeGame", e -> removeGame());
        make = Widgets.button("Make my calendar", "make", e -> make());
        applyTweak = Widgets.button("Apply change", "applyTweak", e -> applyTweak());
        resetTweaks = Widgets.button("Clear changes", "resetTweaks", e -> resetTweaks());
        Widgets.onEnter(availability, this::setAvailability);
        Widgets.onEnter(tweak, this::applyTweak);

        JPanel when = new JPanel(new BorderLayout());
        when.setBorder(javax.swing.BorderFactory.createTitledBorder("When can you play?  (plain wording works too)"));
        when.add(new JLabel("e.g. mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense"), BorderLayout.CENTER);
        JPanel whenInput = new JPanel(new BorderLayout(4, 0));
        whenInput.add(availability, BorderLayout.CENTER);
        whenInput.add(setButton, BorderLayout.EAST);
        JPanel whenBottom = new JPanel(new BorderLayout(0, 4));
        whenBottom.add(whenInput, BorderLayout.NORTH);
        whenBottom.add(slots, BorderLayout.SOUTH);
        when.add(whenBottom, BorderLayout.SOUTH);

        JPanel games = new JPanel(new BorderLayout(4, 4));
        games.setBorder(javax.swing.BorderFactory.createTitledBorder("Games (your saved recommendations are scheduled too)"));
        games.add(Widgets.scroll(gamesTable, null), BorderLayout.CENTER);
        games.add(Widgets.flow(new JLabel("Name"), gameName, new JLabel("Hours (optional)"), gameHours, addButton, removeButton), BorderLayout.SOUTH);

        JPanel top = new JPanel(new GridLayout(1, 2, 8, 8));
        top.add(when);
        top.add(games);

        JPanel controls = new JPanel(new GridLayout(0, 1, 2, 2));
        controls.add(Widgets.flow(new JLabel("Length"), length, new JLabel("Style"), mode, make));
        controls.add(Widgets.flow(new JLabel("Change it (e.g. \"no games on Friday\", \"play Hades first\")"), tweak, applyTweak, resetTweaks));
        controls.add(stale);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(controls, BorderLayout.NORTH);
        center.add(Widgets.scroll(plan, "Your calendar"), BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        refresh();
    }

    @Override
    public String title() { return "Calendar"; }

    @Override
    public void refresh() {
        slots.setText(Render.slots(agent.availability()));
        Widgets.clear(gamesModel);
        for (CalendarGame g : agent.calendarGames()) {
            gamesModel.addRow(new Object[] {g.name(), g.hours() == null ? "(will estimate)" : g.hours()});
        }
        agent.currentCalendar().ifPresent(p -> {
            if (plan.getText().isBlank()) show(p);
        });
        stale.setText(agent.currentCalendar().isPresent() && agent.calendarIsStale()
                ? "Your profile or inputs changed since this was built. Press \"Make my calendar\" to refresh it." : " ");
    }

    private void show(CalendarPlan p) {
        plan.setText(Render.calendar(p));
        plan.setCaretPosition(0);
    }

    private void setAvailability() {
        String text = availability.getText().trim();
        if (text.isEmpty()) {
            ui.error("Say when you can play, e.g. mon,wed 20:00-22:00; sat 14:00-18:00");
            return;
        }
        ui.run("Reading your availability...", () -> agent.setAvailability(text), s -> {
            availability.setText("");
            refresh();
            ui.status("Availability saved. Press \"Make my calendar\".");
        }, setButton);
    }

    private void addGame() {
        String n = gameName.getText().trim();
        Double hours = null;
        String h = gameHours.getText().trim().toLowerCase(Locale.ROOT).replaceAll("\\s*(hours|hrs|hr|h)$", "");
        if (!h.isEmpty()) {
            try {
                hours = Double.parseDouble(h);
            } catch (NumberFormatException e) {
                ui.error("\"" + h + "\" isn't a number of hours.");
                return;
            }
        }
        Double finalHours = hours;
        ui.run("Adding the game...", () -> {
            agent.addCalendarGame(n, finalHours);
            return n;
        }, added -> {
            gameName.setText("");
            gameHours.setText("");
            refresh();
            ui.status("Added " + added + ".");
        }, addButton);
    }

    private void removeGame() {
        int row = gamesTable.getSelectedRow();
        if (row < 0) {
            ui.error("Select one of the games you added first.");
            return;
        }
        String n = String.valueOf(gamesModel.getValueAt(row, 0));
        agent.removeCalendarGame(n);
        refresh();
        ui.status("Removed " + n + ".");
    }

    private void make() {
        int weeks = length.getSelectedIndex() + 1;
        ScheduleMode m = mode.getSelectedIndex() == 1 ? ScheduleMode.MIX : ScheduleMode.FOCUS;
        ui.run("Estimating playtimes and building your calendar...", () -> agent.createCalendar(weeks, m), p -> {
            show(p);
            refresh();
            ui.status("Calendar ready.");
        }, make, applyTweak);
    }

    private void applyTweak() {
        String t = tweak.getText().trim();
        if (t.isEmpty()) return;
        ui.run("Updating your calendar...", () -> agent.refineCalendar(t), p -> {
            show(p);
            tweak.setText("");
            ui.status("Calendar updated.");
        }, make, applyTweak);
    }

    private void resetTweaks() {
        agent.resetCalendarAdjustments();
        ui.status("Changes cleared. Press \"Make my calendar\" to rebuild.");
    }
}
