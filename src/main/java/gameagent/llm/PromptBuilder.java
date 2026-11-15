package gameagent.llm;

import java.util.List;

/**
 * All prompt text lives here so it can be tuned (and tested) in one place.
 * Every prompt demands a single JSON object so replies can be parsed deterministically.
 */
public final class PromptBuilder {
    private PromptBuilder() {}

    /** JSON shape the model uses to describe profile changes (parsed by ProfileUpdate.fromJson). */
    public static final String UPDATE_SCHEMA = """
            {"likes":[{"category":"genre|tag|game|platform","name":"..."}],
             "dislikes":[{"category":"genre|tag|game|platform","name":"..."}],
             "remove":[{"category":"genre|tag|game|platform","name":"..."}],
             "notes":["free-text constraint, e.g. 'prefers sessions under 1 hour'"]}
            Use "genre" for broad genres (Roguelike, RPG, Strategy), "tag" for themes/mechanics (Co-op, Cozy, Pixel Graphics),
            "game" for specific titles, "platform" for PC/PS5/Switch/etc. Use empty lists when nothing applies.""";

    private static final String EXPERT = "You are an expert on video games across all platforms and eras. ";

    // ------------------------------------------------------------------ recommendations

    public static String candidatesSystem() {
        return EXPERT + """
                You suggest REAL, released video games only, using their exact official English titles.
                Never invent games. Respect the user's dislikes and notes/constraints.
                Reply with ONLY this JSON: {"candidates":[{"name":"Exact Title","why":"short reason"}]}""";
    }

    public static String candidatesUser(String profile, String query, int howMany, List<String> avoid) {
        StringBuilder sb = new StringBuilder();
        sb.append("User profile:\n").append(profile).append("\n\n");
        if (query != null && !query.isBlank()) sb.append("The user's current request (highest priority): ").append(query).append("\n\n");
        sb.append("Suggest ").append(howMany).append(" diverse candidate games the user is likely to enjoy. ")
                .append("Mix well-known picks with a few lesser-known ones.\n");
        if (!avoid.isEmpty()) sb.append("Do NOT suggest any of these (already known/shown): ").append(String.join(", ", avoid)).append('\n');
        return sb.toString();
    }

    public static String rankSystem() {
        return EXPERT + """
                You choose and explain game recommendations. Only choose from the candidate list given.
                Reply with ONLY this JSON:
                {"recommendations":[{"name":"exact name from the list","score":0-100,"reason":"1-2 sentences tying the game to THIS user's stated tastes"}]}
                Order best first.""";
    }

    public static String rankUser(String profile, String query, String candidateBlock, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("User profile:\n").append(profile).append("\n\n");
        if (query != null && !query.isBlank()) sb.append("Current request: ").append(query).append("\n\n");
        sb.append("Candidates (verified real games with store data):\n").append(candidateBlock).append("\n\n");
        sb.append("Pick the best ").append(count).append(". Score = how well it fits this user (0-100).");
        return sb.toString();
    }

    public static String curveballSystem() {
        return EXPERT + """
                You power a "curveball roulette": surprise the user with exactly two games.
                One must be currently POPULAR (choose it from the trending list provided).
                One must be NICHE: a lesser-known, real game that still loosely relates to the user's taste (or a pleasant surprise if the profile is empty).
                Never suggest games the user dislikes. Reply with ONLY this JSON:
                {"popular":{"name":"title from the trending list","reason":"..."},"niche":{"name":"Exact Title","reason":"..."}}""";
    }

    public static String curveballUser(String profile, List<String> trending, List<String> avoid, String hint) {
        StringBuilder sb = new StringBuilder();
        sb.append("User profile:\n").append(profile).append("\n\n");
        if (hint != null && !hint.isBlank()) sb.append("Optional user input: ").append(hint).append("\n\n");
        sb.append("Trending now: ").append(trending.isEmpty() ? "(unavailable - pick a widely popular recent game)" : String.join(", ", trending)).append('\n');
        if (!avoid.isEmpty()) sb.append("Avoid these: ").append(String.join(", ", avoid)).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ news

    public static String newsSystem() {
        return "You curate a personalised gaming news feed. Reply with ONLY this JSON: "
                + "{\"picks\":[{\"index\":0,\"why\":\"short reason this matters to the user\"}]} "
                + "Use the numeric index of each headline. Best first. Skip clickbait and irrelevant items.";
    }

    public static String newsUser(String profile, String topic, String headlines, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("User profile:\n").append(profile).append("\n\n");
        if (topic != null && !topic.isBlank()) sb.append("User asked specifically about: ").append(topic).append("\n\n");
        sb.append("Headlines:\n").append(headlines).append("\n\nPick up to ").append(limit).append(" most relevant.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ profile learning

    public static String refinementQuestionsSystem() {
        return "You interview a gamer to learn their tastes. Ask short, concrete, friendly questions that fill the listed gaps. "
                + "Never repeat what is already known. Reply with ONLY this JSON: {\"questions\":[\"...\"]}";
    }

    public static String refinementQuestionsUser(String profile, List<String> gaps, int count) {
        return "Known profile:\n" + profile + "\n\nGaps to fill:\n- " + String.join("\n- ", gaps)
                + "\n\nWrite " + count + " questions (one topic each).";
    }

    public static String interpretSystem() {
        return "You convert a gamer's free-text statements into structured profile updates. "
                + "Only record what the user actually said or clearly implied. Reply with ONLY this JSON:\n" + UPDATE_SCHEMA;
    }

    public static String refinementInterpretUser(String profile, String qaBlock) {
        return "Current profile:\n" + profile + "\n\nQuestions and the user's answers:\n" + qaBlock;
    }

    public static String feedbackInterpretUser(String profile, String game, int rating, String comment) {
        return "Current profile:\n" + profile + "\n\nThe user rated \"" + game + "\" " + rating + "/5 and wrote: \""
                + comment + "\".\nExtract any preferences this reveals (what they liked/disliked about it: mechanics, genres, themes, length, difficulty...). "
                + "Do not add the game itself.";
    }

    // ------------------------------------------------------------------ calendar

    public static String playtimeSystem() {
        return EXPERT + """
                For each game give the typical hours to finish the main content (or a sensible number of hours for endless games),
                and up to 3 vibes chosen ONLY from: chill, intense, story, competitive, social, puzzle, short-session.
                Reply with ONLY this JSON: {"games":[{"name":"exact name as given","hours":12.5,"vibes":["chill"]}]}""";
    }

    public static String playtimeUser(List<String> games) {
        return "Estimate playtime for these games:\n- " + String.join("\n- ", games);
    }

    public static String availabilitySystem() {
        return "You turn a gamer's description of when they can play into weekly time blocks. "
                + "Use 24-hour HH:mm times within a single day. Days are mon,tue,wed,thu,fri,sat,sun. "
                + "Only include blocks the user actually described. Reply with ONLY this JSON: "
                + "{\"slots\":[{\"days\":[\"mon\",\"wed\"],\"start\":\"20:00\",\"end\":\"22:00\",\"vibe\":\"chill or empty\"}]}";
    }

    public static String calendarRefineSystem() {
        return "You convert a gamer's request to adjust their gaming calendar into settings. Only include what they asked for. "
                + "Reply with ONLY this JSON: {\"reset\":false,\"blockDays\":[\"fri\"],\"maxSessionMinutes\":0,"
                + "\"prioritize\":[\"game names to schedule first\"],\"exclude\":[\"game names to leave out\"],\"mode\":\"focus|mix|\"}. "
                + "Use reset=true only if they want to clear earlier adjustments. Use maxSessionMinutes 0 and mode empty when unchanged.";
    }

    public static String calendarRefineUser(String profile, String current, String instruction) {
        return "Profile:\n" + profile + "\n\nCurrent calendar adjustments: " + current + "\n\nThe user says: " + instruction;
    }

    // ------------------------------------------------------------------ chat agent

    public static String chatSystem(String profile, String toolDescriptions) {
        return """
                You are GameAgent, a friendly, knowledgeable assistant that helps people find video games they will love.
                You keep a persistent profile of the user's tastes and you can use tools. Be concise and conversational.

                === USER PROFILE (long-term memory) ===
                %s

                === TOOLS ===
                %s

                === RULES ===
                - Whenever the user states a like, dislike, platform, budget, session length or other preference, call update_preferences FIRST so it is remembered.
                - When the user wants game suggestions (even vaguely), call recommend_games with their request in "query". Results are shown to the user automatically with details; do not re-list them in your reply, just comment briefly.
                - Use search_games to check facts about a specific title. Use get_news for gaming news. Use get_profile only if you need a refresher.
                - When the user wants a gaming schedule or calendar, or tells you when they can play, call plan_calendar. The calendar is shown automatically; just comment briefly.
                - Never invent games, prices or release dates; rely on tool results.
                - If the request is unclear, ask ONE short clarifying question instead of guessing.

                === RESPONSE FORMAT ===
                Reply with ONLY one JSON object, no markdown:
                {"tool": "<tool name, or null when you are done>", "args": {...}, "reply": "<message for the user; required when tool is null>"}
                After a tool runs you will receive its result as a new message and may call another tool or finish.
                """.formatted(profile, toolDescriptions);
    }
}
