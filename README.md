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
| Interest log | `/history [n]` | Deterministic |
| Conversational agent with tools (remembers preferences while you talk, then recommends) | type normally | Agent loop |

**Not implemented yet:** Steam library connection, gaming calendar, friend recommendations, playtime estimator, GUI.

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
recommend/  RecommendationStrategy (Personalized, Curveball), RecommendationFactory, GameCatalog
profile/    UserProfile, ProfileManager (+ listeners), JSON persistence
news/       NewsSource (RSS adapter), NewsService
data/       GameDataSource (Steam store adapter)
llm/        LLMClient (Gemini, OpenAI-compatible), ResilientLLMClient, PromptBuilder
```

| Pattern | Where | Problem it solves |
|---------|-------|-------------------|
| Facade | `AgentController` | UIs (CLI now, GUI later) use one class instead of every service |
| Strategy | `RecommendationStrategy` / `PersonalizedStrategy`, `CurveballStrategy` | Different recommendation algorithms behind one interface |
| Factory | `RecommendationFactory`, `LLMClientFactory` | Central construction of strategies / LLM clients |
| Observer | `ProfileManager` -> `ProfileListener` (`AutoSaveListener`, `GameCatalog`, `NewsService`) | Profile changes automatically save, flag the catalogue stale and drop cached news rankings |
| Adapter | `SteamStoreAdapter`, `RssNewsSource` | Third-party formats are converted to our `Game` / `NewsItem` |
| Decorator | `ResilientLLMClient` | Adds throttling and retry/backoff to any LLM client |
| Command | `cli/commands/*` | Each CLI command is an object; easy to add or reuse in a GUI |

How a recommendation is made (`PersonalizedStrategy`): LLM proposes candidates from your profile and request,
each is checked against the Steam catalogue (hallucinated titles are dropped), the LLM ranks and explains the verified
ones, already-known games are filtered, and the result is saved to your catalogue.

## Tests

```
test.bat         (Windows)
./test.sh        (macOS / Linux)
```

Runs offline using stub servers, a fake Steam and a scripted fake LLM (test code only). It does not call Gemini or Steam.
These are smoke/verification tests written without a test framework; a JUnit suite is a planned improvement.

## Known limitations

* The Steam store endpoints and the Gemini endpoint are exercised in tests only through stubs that copy their documented
  response shape; the first real run is the first live check. Please report any parsing problems.
* Console exclusives are not in the Steam catalogue, so they show as "(unverified)".
* Free-tier rate limits: calls are throttled (`LLM_MIN_INTERVAL_MS`) and retried, so replies can take several seconds.
