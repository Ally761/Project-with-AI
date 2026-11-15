package gameagent.model;

import java.util.List;

/** What a rating changed in the profile, plus anything that could not be done (e.g. the AI was unavailable). */
public record FeedbackResult(List<String> changes, List<String> warnings) {}
