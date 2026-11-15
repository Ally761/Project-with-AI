package gameagent.cli.commands;

import gameagent.model.ApplyResult;
import gameagent.cli.CliContext;
import gameagent.profile.ConflictResolver;
import gameagent.profile.ProfileUpdate;

class NoteCommand extends BaseCommand {
    NoteCommand() {
        super("note", "/note <free text>", "Save a constraint in your own words (budget, session length, difficulty...).");
    }

    @Override
    public void execute(String args, CliContext ctx) {
        if (args.isBlank()) {
            ctx.println("Usage: " + usage());
            return;
        }
        ApplyResult r = ctx.agent().updateProfile(new ProfileUpdate("user").note(args), ConflictResolver.KEEP_EXISTING);
        r.applied().forEach(s -> ctx.println("  + " + s));
        r.skipped().forEach(s -> ctx.println("  - " + s));
    }
}
