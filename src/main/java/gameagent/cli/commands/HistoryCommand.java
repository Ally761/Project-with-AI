package gameagent.cli.commands;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import gameagent.cli.CliContext;
import gameagent.model.LogEntry;

class HistoryCommand extends BaseCommand {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    HistoryCommand() {
        super("history", "/history [n]", "Show the log of your recent interests and interactions (default 15).");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        int n = 15;
        try {
            if (!args.isBlank()) n = Math.max(1, Integer.parseInt(args.trim()));
        } catch (NumberFormatException ignored) { }
        List<LogEntry> log = ctx.agent().profile().log();
        if (log.isEmpty()) {
            ctx.println("Nothing logged yet.");
            return;
        }
        for (LogEntry e : log.subList(Math.max(0, log.size() - n), log.size())) {
            String text = e.text().length() > 110 ? e.text().substring(0, 107) + "..." : e.text();
            ctx.println(FMT.format(e.time()) + "  [" + e.type() + "] " + text);
        }
    }
}
