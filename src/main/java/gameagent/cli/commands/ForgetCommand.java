package gameagent.cli.commands;

import gameagent.model.ApplyResult;
import gameagent.cli.CliContext;
import gameagent.profile.Category;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileUpdate;

class ForgetCommand extends BaseCommand {
    ForgetCommand() {
        super("forget", "/forget <genre|tag|game|platform> <name>  |  /forget note <number>",
                "Remove a stored preference or note.", "remove");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            ctx.println("Usage: " + usage());
            return;
        }
        if (parts[0].equalsIgnoreCase("note")) {
            try {
                ctx.println(ctx.agent().removeNote(Integer.parseInt(parts[1].trim())) ? "  - Note removed." : "  No such note number (see /profile).");
            } catch (NumberFormatException e) {
                ctx.println("Usage: /forget note <number>");
            }
            return;
        }
        if (!LikeCommand.isCategory(parts[0])) {
            ctx.println("Usage: " + usage());
            return;
        }
        ApplyResult r = ctx.agent().updateProfile(
                new ProfileUpdate("user").remove(Category.parse(parts[0]), parts[1]), ConflictResolver.KEEP_EXISTING);
        r.applied().forEach(s -> ctx.println("  - " + s));
        r.skipped().forEach(s -> ctx.println("  " + s));
    }
}
