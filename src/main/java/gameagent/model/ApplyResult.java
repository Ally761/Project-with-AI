package gameagent.model;

import java.util.List;

/** What actually happened when a profile update was applied (for showing the user). */
public record ApplyResult(List<String> applied, List<String> skipped) {
    public boolean changedAnything() { return !applied.isEmpty(); }
}
