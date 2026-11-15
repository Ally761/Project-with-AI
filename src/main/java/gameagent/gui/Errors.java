package gameagent.gui;

import gameagent.llm.LLMException;

/** Turns an exception into the sentence shown to the user (same wording rules as the command line). */
public final class Errors {
    private Errors() {}

    public static String describe(Throwable t) {
        if (t == null) return "Unknown problem.";
        String msg = t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
        if (t instanceof LLMException) return "AI service problem: " + msg;
        boolean ours = t instanceof IllegalArgumentException || t.getClass().getName().startsWith("gameagent.");
        return ours ? msg : "Something went wrong: " + t.getClass().getSimpleName() + ": " + msg;
    }
}
