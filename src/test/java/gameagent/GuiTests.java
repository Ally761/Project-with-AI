package gameagent;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import gameagent.agent.AgentController;
import gameagent.gui.Errors;
import gameagent.gui.FeaturePanel;
import gameagent.gui.GuiApp;
import gameagent.gui.GuiContext;
import gameagent.llm.LLMException;

import static gameagent.AllTests.check;
import static gameagent.AllTests.eq;
import static gameagent.AllTests.test;

/**
 * Drives every GUI tab through its own buttons without opening a window (headless).
 * The window services are replaced by a synchronous test double, so these tests check what each tab asks of the facade
 * and what it shows, not how Swing paints it.
 */
final class GuiTests {
    private GuiTests() {}

    /** Synchronous stand-in for the window: records messages and answers dialogs from a script. */
    static final class TestContext implements GuiContext {
        final List<String> errors = new ArrayList<>(), infos = new ArrayList<>(), statuses = new ArrayList<>(), links = new ArrayList<>();
        final List<String> confirms = new ArrayList<>();
        final LinkedList<List<String>> answers = new LinkedList<>();
        boolean confirmAnswer = true;

        @Override
        public <T> void run(String busy, Callable<T> work, Consumer<T> done, JComponent... lock) {
            try {
                done.accept(work.call());
            } catch (Exception e) {
                error(Errors.describe(e));
            }
        }

        @Override public void status(String text) { statuses.add(text); }
        @Override public void error(String text) { errors.add(text); }
        @Override public void info(String text) { infos.add(text); }
        @Override public boolean confirm(String question) { confirms.add(question); return confirmAnswer; }
        @Override public void openLink(String url) { links.add(url); }

        @Override
        public List<String> askAll(String title, List<String> questions) {
            if (!answers.isEmpty()) return answers.removeFirst();
            List<String> a = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) a.add("I enjoy roguelikes");
            return a;
        }
    }

    static <T extends Component> T find(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c);
            if (c instanceof Container k) {
                T t = find(k, name, type);
                if (t != null) return t;
            }
        }
        return null;
    }

    static <T extends Component> T need(Container root, String name, Class<T> type) {
        T t = find(root, name, type);
        check(t != null, "the screen has a " + type.getSimpleName() + " named " + name);
        return t;
    }

    static void click(Container root, String name) { need(root, name, JButton.class).doClick(); }

    static void type(Container root, String name, String text) { need(root, name, JTextField.class).setText(text); }

    static FeaturePanel panel(List<FeaturePanel> panels, String title) {
        return panels.stream().filter(p -> p.title().equals(title)).findFirst().orElseThrow();
    }

    static AgentController agent(Fakes.ScriptedLLM llm, List<gameagent.news.NewsSource> news, Path dir) {
        return AllTests.controller(llm, new Fakes.FakeSteam(), news, dir);
    }

    static void run() {
        System.setProperty("java.awt.headless", "true");
        System.out.println("GUI (headless: each tab driven through its buttons)");

        test("gui: every feature has a tab, and the tabs refresh when shown", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            List<FeaturePanel> panels = GuiApp.panels(c, ui);
            eq(List.of("Chat", "Recommend", "Profile", "Feedback", "News", "Steam", "Calendar", "Playtime", "Friends"),
                    panels.stream().map(FeaturePanel::title).toList(), "tab order");
            JTabbedPane tabs = GuiApp.tabs(panels);
            eq(9, tabs.getTabCount(), "tabs");
            FeaturePanel steam = panel(panels, "Steam");
            check(need(steam, "steamGames", JTable.class).getRowCount() == 0, "nothing linked yet");
            c.linkSteam("hadesfan");
            tabs.setSelectedIndex(5);
            check(need(steam, "steamGames", JTable.class).getRowCount() > 0, "showing the Steam tab re-reads the profile");
            check(ui.errors.isEmpty(), "no errors: " + ui.errors);
        });

        test("gui: chat sends text to the agent and shows what it remembered", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel chat = panel(GuiApp.panels(c, ui), "Chat");
            type(chat, "chatInput", "I loved Hades and Dead Cells but I hate grinding. Recommend something.");
            click(chat, "chatSend");
            String t = need(chat, "transcript", JTextArea.class).getText();
            check(t.contains("You: I loved Hades"), "the user's line is shown: " + t);
            check(t.contains("Agent:"), "the agent's answer is shown: " + t);
            check(ui.errors.isEmpty(), "no errors: " + ui.errors);
            check(need(chat, "chatInput", JTextField.class).getText().isEmpty(), "input is cleared");
            type(chat, "chatInput", "   ");
            click(chat, "chatSend");
            check(!need(chat, "transcript", JTextArea.class).getText().contains("You:    "), "blank messages are ignored");
        });

        test("gui: a failing AI service is reported, not swallowed", () -> {
            TestContext ui = new TestContext();
            Fakes.ScriptedLLM down = new Fakes.ScriptedLLM(Fakes::smartScript);
            down.down = true;
            AgentController c = agent(down, List.of(), AllTests.tmp());
            FeaturePanel chat = panel(GuiApp.panels(c, ui), "Chat");
            type(chat, "chatInput", "hello");
            click(chat, "chatSend");
            check(ui.errors.size() == 1 && ui.errors.get(0).startsWith("AI service problem"), "reported: " + ui.errors);
            check(Errors.describe(new LLMException("x")).startsWith("AI service problem"), "LLM wording");
            check(Errors.describe(new IllegalStateException("boom")).startsWith("Something went wrong"), "unexpected failures say so");
        });

        test("gui: recommend, curveball, catalogue and rating", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel rec = panel(GuiApp.panels(c, ui), "Recommend");
            JTable results = need(rec, "results.table", JTable.class);
            type(rec, "query", "fast combat");
            click(rec, "recommend");
            check(ui.errors.isEmpty(), "no errors: " + ui.errors);
            check(results.getRowCount() > 0, "recommendations are listed");
            check(need(rec, "detail", JTextArea.class).getText().length() > 0, "the first row is selected and described");
            int found = results.getRowCount();
            click(rec, "curveball");
            eq(2, results.getRowCount(), "curveball shows one popular and one niche pick");
            click(rec, "catalog");
            check(results.getRowCount() >= found, "the catalogue holds earlier picks");
            results.setRowSelectionInterval(0, 0);
            ui.answers.add(List.of("4", "great combat"));
            click(rec, "rate");
            eq(1, c.profile().feedback().size(), "rating saved through the facade");
            eq(4, c.profile().feedback().get(0).rating(), "rating value");
            ui.answers.add(List.of("lots", ""));
            int before = ui.errors.size();
            click(rec, "rate");
            check(ui.errors.size() == before + 1 && ui.errors.get(before).contains("number from 1 to 5"), "a bad rating is explained");
        });

        test("gui: profile: like, dislike with conflict question, remove, notes, reset", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel prof = panel(GuiApp.panels(c, ui), "Profile");
            JTable likes = need(prof, "likes", JTable.class), dislikes = need(prof, "dislikes", JTable.class);
            type(prof, "prefName", "Roguelike");
            click(prof, "like");
            eq(1, likes.getRowCount(), "like listed");
            eq("Roguelike", likes.getValueAt(0, 1), "name");
            type(prof, "prefName", "Roguelike");
            click(prof, "dislike");
            eq(1, ui.confirms.size(), "a conflicting opinion asks which one to keep");
            eq(0, likes.getRowCount(), "switched after yes");
            eq(1, dislikes.getRowCount(), "now a dislike");
            dislikes.setRowSelectionInterval(0, 0);
            click(prof, "removePref");
            eq(0, dislikes.getRowCount(), "removed");
            click(prof, "removePref");
            check(ui.errors.size() == 1, "removing with nothing selected is explained: " + ui.errors);
            type(prof, "noteText", "I only have 45 minutes a night");
            click(prof, "addNote");
            eq(1, need(prof, "notes", JList.class).getModel().getSize(), "note listed");
            need(prof, "notes", JList.class).setSelectedIndex(0);
            click(prof, "removeNote");
            eq(0, c.profile().notes().size(), "note removed");
            type(prof, "prefName", "Hades");
            click(prof, "like");
            ui.confirmAnswer = false;
            click(prof, "reset");
            check(!c.profile().isEmpty(), "declining the question keeps the profile");
            ui.confirmAnswer = true;
            click(prof, "reset");
            check(c.profile().isEmpty(), "confirming clears it");
        });

        test("gui: feedback form and the preference interview", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel fb = panel(GuiApp.panels(c, ui), "Feedback");
            click(fb, "submitFeedback");
            check(ui.errors.size() == 1, "a game name is required");
            type(fb, "feedbackGame", "Hades");
            type(fb, "feedbackComment", "great combat, weak story");
            click(fb, "submitFeedback");
            eq(1, need(fb, "feedbackHistory", JTable.class).getRowCount(), "rating listed");
            check(need(fb, "feedbackResult", JTextArea.class).getText().contains("Saved your 5/5 for Hades"), "result shown");
            ui.answers.add(List.of("I like short sessions", "", "", ""));
            click(fb, "refine");
            check(ui.errors.size() == 1, "no new errors: " + ui.errors);
            check(!need(fb, "feedbackResult", JTextArea.class).getText().isBlank(), "interview outcome shown");
            ui.answers.add(List.of("", "", "", ""));
            click(fb, "refine");
            check(ui.statuses.stream().anyMatch(s -> s.contains("No answers given")), "skipping every question changes nothing");
            ui.answers.add(null);
            click(fb, "refine");
            check(ui.errors.size() == 1, "cancelling the dialog is not an error");
        });

        test("gui: news is listed and articles open in the browser", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), AllTests.rssNews(), AllTests.tmp());
            FeaturePanel news = panel(GuiApp.panels(c, ui), "News");
            click(news, "openArticle");
            check(ui.errors.size() == 1, "nothing to open yet");
            click(news, "fetchNews");
            JTable t = need(news, "newsTable", JTable.class);
            check(t.getRowCount() > 0, "stories listed: " + ui.errors);
            t.setRowSelectionInterval(0, 0);
            click(news, "openArticle");
            eq(1, ui.links.size(), "the article link was opened");
            check(ui.links.get(0).startsWith("http"), "a real link: " + ui.links);
        });

        test("gui: steam link, refresh and unlink", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel steam = panel(GuiApp.panels(c, ui), "Steam");
            click(steam, "steamLink");
            check(ui.errors.size() == 1, "a link is required");
            type(steam, "steamProfile", "hadesfan");
            click(steam, "steamLink");
            check(c.profile().steamLink().isPresent(), "linked through the facade");
            check(need(steam, "steamGames", JTable.class).getRowCount() > 0, "most-played games shown");
            check(need(steam, "steamResult", JTextArea.class).getText().contains("Linked"), "outcome shown");
            click(steam, "steamRefresh");
            click(steam, "steamUnlink");
            check(c.profile().steamLink().isEmpty(), "unlinked");
            eq(0, need(steam, "steamGames", JTable.class).getRowCount(), "list emptied");
            eq(2, ui.confirms.size(), "unlinking asks twice: forget the library, and remove imported tastes");
            type(steam, "steamProfile", "https://steamcommunity.com/id/nobody/");
            int before = ui.errors.size();
            click(steam, "steamLink");
            check(ui.errors.size() == before + 1, "an unknown profile is reported: " + ui.errors);
        });

        test("gui: calendar: availability, games, plan, tweak", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel cal = panel(GuiApp.panels(c, ui), "Calendar");
            click(cal, "make");
            check(ui.errors.size() == 1 && ui.errors.get(0).contains("when you can play"), "needs availability first: " + ui.errors);
            type(cal, "availability", "mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense");
            click(cal, "setAvailability");
            eq(3, c.availability().size(), "availability saved");
            type(cal, "calendarGameName", "Celeste");
            type(cal, "calendarGameHours", "8h");
            click(cal, "addGame");
            type(cal, "calendarGameName", "Hades");
            click(cal, "addGame");
            eq(2, need(cal, "calendarGames", JTable.class).getRowCount(), "games listed");
            type(cal, "calendarGameName", "Oops");
            type(cal, "calendarGameHours", "lots");
            int before = ui.errors.size();
            click(cal, "addGame");
            check(ui.errors.size() == before + 1, "bad hours are explained");
            need(cal, "calendarMode", JComboBox.class).setSelectedIndex(1);
            need(cal, "calendarLength", JComboBox.class).setSelectedIndex(3);
            click(cal, "make");
            String plan = need(cal, "plan", JTextArea.class).getText();
            check(plan.contains("Gaming calendar") && plan.contains("4 weeks"), "plan shown: " + plan);
            eq(gameagent.calendar.ScheduleMode.MIX, c.currentCalendar().orElseThrow().mode(), "mix mode chosen in the combo box");
            type(cal, "tweak", "no games on Friday");
            click(cal, "applyTweak");
            check(need(cal, "plan", JTextArea.class).getText().contains("Gaming calendar"), "plan re-shown after the change");
            click(cal, "resetTweaks");
            need(cal, "calendarGames", JTable.class).setRowSelectionInterval(0, 0);
            click(cal, "removeGame");
            eq(1, c.calendarGames().size(), "game removed");
        });

        test("gui: playtime estimate", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            FeaturePanel pt = panel(GuiApp.panels(c, ui), "Playtime");
            click(pt, "estimate");
            check(ui.errors.size() == 1, "a game name is required");
            type(pt, "playtimeGame", "Hades");
            click(pt, "estimate");
            String r = need(pt, "playtimeResult", JTextArea.class).getText();
            check(r.contains("Typical time to beat") && r.contains("For you"), "estimate shown: " + r + ui.errors);
        });

        test("gui: friends: add, compare, recommend, remove", () -> {
            TestContext ui = new TestContext();
            AgentController c = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            c.linkSteam("hadesfan");
            FeaturePanel fr = panel(GuiApp.panels(c, ui), "Friends");
            type(fr, "friendLink", "https://steamcommunity.com/id/privateguy/");
            click(fr, "addFriend");
            check(ui.errors.size() == 1 && ui.errors.get(0).contains("different friend"), "private profile explained: " + ui.errors);
            type(fr, "friendLink", "alexfriend");
            click(fr, "addFriend");
            type(fr, "friendLink", "samfriend");
            click(fr, "addFriend");
            JTable t = need(fr, "friendsTable", JTable.class);
            eq(2, t.getRowCount(), "friends listed");
            int before = ui.errors.size();
            click(fr, "recommendSelected");
            check(ui.errors.size() == before + 1, "selecting a friend is required");
            t.setRowSelectionInterval(0, 0);
            click(fr, "common");
            check(need(fr, "commonGames", JTextArea.class).getText().contains("You both play"), "comparison shown");
            click(fr, "recommendSelected");
            check(need(fr, "friendResults.table", JTable.class).getRowCount() > 0, "the selected friend's picks are listed: " + ui.errors);
            check(ui.errors.size() == before + 1, "no new errors: " + ui.errors);
            click(fr, "recommendSelected");
            check(ui.errors.size() == before + 2 && ui.errors.get(before + 1).contains("doesn't play anything new"),
                    "when everything was already shown, the screen says so: " + ui.errors);
            click(fr, "removeFriend");
            eq(1, c.friends().size(), "friend removed");
            click(fr, "refreshFriends");

            TestContext ui2 = new TestContext();
            AgentController c2 = agent(new Fakes.ScriptedLLM(Fakes::smartScript), List.of(), AllTests.tmp());
            c2.linkSteam("hadesfan");
            FeaturePanel all = panel(GuiApp.panels(c2, ui2), "Friends");
            type(all, "friendLink", "alexfriend");
            click(all, "addFriend");
            type(all, "friendLink", "samfriend");
            click(all, "addFriend");
            click(all, "recommendAll");
            check(need(all, "friendResults.table", JTable.class).getRowCount() > 0, "picks from all friends: " + ui2.errors);
            eq("Risk of Rain 2", String.valueOf(need(all, "friendResults.table", JTable.class).getValueAt(0, 1)), "played by both friends, so first");
        });

        test("gui: the screens are built only from the facade (no hard-wired services or dialogs inside panels)", () -> {
            Path gui = Path.of("src/main/java/gameagent/gui");
            try (var files = java.nio.file.Files.list(gui)) {
                for (Path f : files.filter(p -> p.toString().endsWith("Panel.java")).toList()) {
                    String s = java.nio.file.Files.readString(f);
                    check(!s.contains("JOptionPane"), f.getFileName() + " asks through GuiContext, not dialogs");
                    check(!s.contains("SwingWorker") && !s.contains("new Thread"), f.getFileName() + " runs slow work through GuiContext.run");
                }
            }
        });
    }
}
