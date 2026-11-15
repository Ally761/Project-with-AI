package gameagent.cli.commands;

import gameagent.model.RefinementAnswer;
import gameagent.model.ApplyResult;
import java.util.ArrayList;
import java.util.List;

import gameagent.cli.CliContext;

class RefineCommand extends BaseCommand {
    RefineCommand() {
        super("refine", "/refine", "Answer a few questions so I can fill gaps in your profile (optional).");
    }

    @Override
    public void execute(String args, CliContext ctx) throws Exception {
        List<String> questions = ctx.agent().refinementQuestions(4);
        if (questions.isEmpty()) {
            ctx.println("Your profile already covers the basics. Use /like, /dislike or chat to fine-tune it.");
            return;
        }
        ctx.println("A few quick questions (press Enter to skip one, type 'stop' to finish):");
        List<RefinementAnswer> answers = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            String a = ctx.ask("\n" + (i + 1) + ". " + questions.get(i) + "\n> ");
            if (a == null || a.trim().equalsIgnoreCase("stop")) break;
            answers.add(new RefinementAnswer(questions.get(i), a));
        }
        ctx.println("\nUpdating your profile...");
        ApplyResult r = ctx.agent().submitRefinement(answers);
        if (r.applied().isEmpty()) ctx.println("Nothing new learned.");
        r.applied().forEach(s -> ctx.println("  + " + s));
        r.skipped().forEach(s -> ctx.println("  - " + s));
    }
}
