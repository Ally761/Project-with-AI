# GameAgent - AI Game Recommendation Agent (CLI)

An AI agent you chat with to build a personal game catalogue. It remembers what you like and dislike,
refines that profile over time (from chat, ratings and short interviews), verifies every recommendation against the
real Steam store, and shows gaming news ranked for your tastes.

This is the **command-line version**. A GUI can sit on the same `AgentController` facade.

## Features implemented so far

| Feature | How to use | AI involvement |
|---------|-----------|----------------|
| Personalised recommendations + saved catalogue | `/recommend [n] [request]`, `/catalog`, or just chat | Hybrid: LLM proposes and ranks, Steam verifies |
| Profile / preferences (weighted likes, dislikes, notes) | `/profile`, `/like`, `/dislike`, `/forget`, `/note` | Hybrid |
| Game feedback (rating + comment) | `/rate Hades \| 5 \| great combat, weak story` | Hybrid: rating rules are deterministic, comment read by LLM |
| Preference refinement interview | `/refine` | AI: finds gaps, asks questions, interprets answers |
| Curveball roulette (1 popular + 1 niche) | `/curveball [hint]` | Hybrid: real Steam top sellers + LLM |
| Gaming news panel | `/news [topic]` | Hybrid: RSS feeds, LLM ranking, keyword fallback, stored copy when offline |
| Steam profile link (most-played games from your public profile URL) | `/steam <profile link>`, `/steam refresh`, `/steam top`, `/steam unlink`, or paste your link in chat | Hybrid: playtime rules are deterministic, the AI uses the result |
| Gaming calendar (weekly or monthly play schedule) | `/calendar when ...`, `/calendar add <game> \| <hours>`, `/calendar make [weeks\|month] [focus\|mix]`, `/calendar tweak <change>`, or ask in chat | Hybrid: the schedule is computed by rules so it always fits your time; the AI reads free-text availability, estimates playtime and applies tweaks |
| Playtime estimator ("how long will this take me?") | `/playtime <game>`, or ask in chat | Hybrid: IGDB community data (AI estimate as fallback) for the typical time; rules adjust it to your pace from your Steam hours |
| Friend recommendations (from your friends' public Steam profiles) | `/friends add <link>`, `/friends recommend [name] [n]`, `/friends common <name>`, or ask in chat | Hybrid: rules collect, filter and score friends' games against your tastes; the AI picks and explains |
| Interest log | `/history [n]` | Deterministic |
| Conversational agent with tools (remembers preferences while you talk, then recommends) | type normally | Agent loop |

**Not implemented yet:** GUI.

## Prerequisites

* JDK 17 or newer (`java -version`). No other dependencies: HTTP, XML and JSON use only the JDK.
* An LLM API key. The default is Google Gemini (free tier): create a key at <https://aistudio.google.com/apikey>.

## Setup

1. Copy `.env.example` to `.env` (the real `.env` is git-ignored, never commit it).
2. Edit `.env` and set `LLM_API_KEY=` to your key. Optionally change `LLM_MODEL`
   (check which Gemini models your account offers; default is `gemini-3.7-flash`).

## Run

```
run.bat          (Windows)
./run.sh         (macOS / Linux)
```

or with Maven: `mvn package` then `java -jar target/game-agent.jar` (run from the project folder so `.env` is found).

Your data is stored in `data/` (`profile.json`, `catalog.json`, `news-cache.json`), all git-ignored.
Without a key the app still starts; profile commands work, AI commands show a clear error.

## Example session

```
you> I loved Hades and Dead Cells but I hate grinding. I only have about 45 minutes a night.
  (remembered) Likes game "Hades" ...
agent> Got it! Here are some picks that fit short, punchy sessions.
1. Hollow Knight  (match 88%) ...
you> /rate Hades | 5 | great combat, weak story
you> /curveball
you> /news roguelike
you> /refine
```

Type `/help` for the full list.

## Switching the LLM

Only `LLM_PROVIDER`, `LLM_MODEL`, `LLM_API_KEY` (and optionally `LLM_BASE_URL`) in `.env` change.

* `LLM_PROVIDER=gemini` - Google Gemini.
* `LLM_PROVIDER=openai` - anything speaking the OpenAI chat-completions protocol (OpenAI, Groq, OpenRouter, a local Ollama with `LLM_BASE_URL=http://localhost:11434/v1`, ...).

A new provider is one class implementing `llm/LLMClient` plus one `case` in `LLMClientFactory`.
The agent uses a plain-JSON tool protocol instead of provider-specific function calling, so any capable model works.

### Overloaded model (HTTP 503) or rate limits (429)

Calls are retried with backoff and every retry is printed. If the main model keeps failing, the app switches to
`LLM_FALLBACK_MODELS` (default `gemini-3.5-flash,gemini-3.5-flash-lite`). If everything fails you get the exact error from each model,
within roughly a minute, rather than a silent wait. Set `LLM_MODEL` / `LLM_FALLBACK_MODELS` to models your account offers.
`LLM_THINKING_LEVEL=low` (Gemini 3) keeps requests light; use `LLM_THINKING_BUDGET` for 2.5 models.

## Architecture and design patterns

```
cli/        CliApp, Command objects            -> talk only to AgentController
agent/      AgentController (Facade), ChatAgent (tool loop), Tools, FeedbackService, RefinementService
recommend/  RecommendationStrategy (Personalized, Curveball, Friends), Recommender (Factory Method creators), GameCatalog
friends/    FriendService (friends list), FriendRoster (read-only view for strategies)
profile/    UserProfile, ProfileManager (+ listeners), JSON persistence
news/       NewsSource (RSS adapter), NewsService
calendar/   CalendarService, SchedulingStrategy (Focus, Variety), CalendarStore
playtime/   PlaytimeService, PlaytimeStrategy (General, Pace), PlaytimeEstimator (port), LlmPlaytimeEstimator
steam/      SteamProfileSource (Steam Community adapter), SteamLibraryAnalyzer, SteamLinkService
data/       GameDataSource (Steam store adapter)
llm/        LLMClient (Gemini, OpenAI-compatible), ResilientLLMClient, PromptBuilder
```

| Pattern | Where | Problem it solves |
|---------|-------|-------------------|
| Facade | `AgentController` | UIs (CLI now, GUI later) use one class instead of every service |
| Strategy | `RecommendationStrategy` / `PersonalizedStrategy`, `CurveballStrategy`, `FriendsStrategy` | Different recommendation algorithms behind one interface |
| Strategy (3) | `PlaytimeStrategy` / `GeneralPlaytimeStrategy`, `PacePlaytimeStrategy` | Typical time as is, or scaled to the user's own pace when enough history exists |
| Strategy (2) | `SchedulingStrategy` / `FocusScheduling`, `VarietyScheduling` | Finish games one at a time, or rotate between them, behind one interface |
| Factory Method | `Recommender` (abstract creator with `createStrategy()`), `PersonalizedRecommender`, `CurveballRecommender`, `FriendsRecommender` | Each creator decides which `RecommendationStrategy` to build; the shared run-and-record step lives in the creator |
| Simple factory | `LLMClientFactory` | Builds the configured LLM client (and fallback chain) from settings |
| Observer | `ProfileManager` -> `ProfileListener` (`AutoSaveListener`, `GameCatalog`, `NewsService`, `CalendarService`) | Profile changes automatically save, flag the catalogue and calendar as out of date and drop cached news rankings |
| Adapter | `SteamStoreAdapter`, `SteamCommunityAdapter`, `RssNewsSource`, `IgdbGameLengthSource` | Third-party formats are converted to our `Game` / `NewsItem` |
| Decorator | `ResilientLLMClient` | Adds throttling and retry/backoff to any LLM client |
| Command | `cli/commands/*` | Each CLI command is an object; easy to add or reuse in a GUI |

How a recommendation is made (`PersonalizedStrategy`): LLM proposes candidates from your profile and request,
each is checked against the Steam catalogue (hallucinated titles are dropped), the LLM ranks and explains the verified
ones, already-known games are filtered, and the result is saved to your catalogue.

## Gaming calendar

```
/calendar when mon,wed 20:00-22:00 chill; sat 14:00-18:00 intense     when you can play (weekdays, weekends, daily also work)
/calendar add Celeste | 8                                              a game and how many hours it takes (hours optional)
/calendar make                                                         one week;  /calendar make month mix   four weeks, rotating games
/calendar tweak no games on Friday                                      plain-English changes (also: "play Hades first", "sessions under an hour")
```

Games come from the ones you add plus your saved recommendations (up to 8). Hours you give are used as they are; for other games the
AI estimates the hours and a "vibe" (chill, intense...), and those numbers are shown with a `~`. Slots with a vibe go to games that
match it. `focus` finishes one game before the next; `mix` rotates with sessions of at most 90 minutes.
If your games do not fit in your free time, the calendar says so and offers three options: shorter games, splitting games into
sessions (`mix`), or changing your schedule. The plan is saved in `data/calendar.json` and flagged when your profile changes.

## Friend recommendations

```
/friends add https://steamcommunity.com/id/theirname     add a friend by their public profile link (also: /friends <link>)
/friends recommend [name] [n]                             games your friends play that suit you (one friend, or all)
/friends common <name>                                    what you both have, and what they play that you don't
/friends refresh | remove <name> | /friends               re-read, remove, or list
```

No login or API key is used, only the friend's public profile page. Steam shows only a profile's most-played games to anyone who
isn't signed in, so recommendations are based on those few games per friend; adding several friends gives more to work with.
A private profile can't be read, and you are asked to pick a different friend. Games you already own, have rated or were recently
shown are skipped, and tools such as Wallpaper Engine are ignored. Games several friends play, and games they play a lot, rank higher;
the genres are then compared with your weighted likes and dislikes, and the AI picks the best few and explains each one by naming the
friends who play it. Without the AI, the same ranking is shown with a plain explanation. Friends' data is kept only on your computer
in `data/friends.json` (git-ignored).

## Playtime estimator

`/playtime Hades` (or ask "how long would Hades take me?").

**Typical time.** With IGDB keys configured, the typical time comes from IGDB's community "time to beat" data (documented API,
free), shown with the number of players and the rushed-to-completionist range. HowLongToBeat has no official API, so it is not used.
To enable it: create a free application at https://dev.twitch.tv/console, then put its Client ID and a generated Client Secret in
`.env` as `IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET` (the file is git-ignored). Titles must match exactly ("Hades" never becomes
"Hades II"). If IGDB has no data for a game, or is unreachable, the AI's estimate is used and the answer says so.

**Your pace.** The service looks at your Steam games: where your hours are 40% to 250% of the typical time, you probably played
through once, so those games show your pace. With at least 3 such games, your estimate is the typical time scaled by your median
pace (limited to 60%-160%). With fewer, you get the typical time and are told why. Replayed favourites, abandoned games and games
with no figure are ignored. If you already own the game, your logged hours are shown.

## Linking your Steam profile

`/steam https://steamcommunity.com/id/yourname` reads your **public** profile, the same feed a browser gets without signing in.
There is no login, password or API key. You can paste a profile link (`.../id/name` or `.../profiles/765...`), a 17-digit
Steam ID or just your custom URL name; the link is also recognised when you paste it in chat.

Set these in Steam under Profile > Edit Profile > Privacy Settings: **My profile = Public** and **Game details = Public**.
You can switch them back afterwards because only a snapshot is kept; run `/steam refresh` after making them public again to update it.

**What it can see:** Steam no longer serves a profile's full games list to anyone who isn't signed in (every version of that page
redirects to the login screen). A public profile still lists its **few most-played games with hours**, and that is what gets
imported. The app tells you how many it read. Your other games are not visible, so they can still be recommended.

What it does with those games:

* The ones you've put real time into (2+ hours) become liked games, and genres shared by several of them become liked genres.
* It never overrides you: anything you have already liked or disliked is left alone, and refreshing does not add weight twice.
* Games it knows you own are not recommended, and the AI is told not to suggest them.
* `/steam unlink` forgets the link; it asks whether to also remove the tastes that were imported (what you said yourself stays).

The snapshot is stored in `data/profile.json`, which is git-ignored.

## Tests

```
test.bat         (Windows)
./test.sh        (macOS / Linux)
```

Runs offline using stub servers, a fake Steam and a scripted fake LLM (test code only). It does not call Gemini or Steam.
These are smoke/verification tests written without a test framework; a JUnit suite is a planned improvement.

The suite ends with **design-pattern guards** (`PatternGuards.java`). They fail if a change bypasses the Facade (the CLI must go
through `AgentController`), names a concrete Steam/RSS/LLM class outside the composition root, stops notifying observers on a
profile change, builds a strategy without the factory, breaks decorator stacking, or adds a command/tool that is never registered. Further guards cover the calendar's scheduling strategies and its playtime port.

## Known limitations

* The Steam store endpoints, the Steam Community profile pages and the Gemini endpoint are exercised in tests only through stubs
  that copy their documented response shape; the first real run is the first live check. Please report any parsing problems.
* Steam Community pages are not an official API and are rate-limited per IP; if Steam changes them, `/steam` reports it
  instead of guessing. Reading the whole library would need Steam's Web API with a free API key, which isn't implemented.
* Console exclusives are not in the Steam catalogue, so they show as "(unverified)".
* Free-tier rate limits: calls are throttled (`LLM_MIN_INTERVAL_MS`) and retried, so replies can take several seconds.
