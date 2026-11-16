# GameAgent

A small AI assistant that helps you figure out what to play. Tell it what you like (or don't), and it recommends games,
shows gaming news picked for you, plans when to play them and guesses how long they'll take you.
It runs as a window (GUI) or in the terminal (CLI) - same features either way.

## What you need

- **Java 17 or newer** (check with `java -version`). That's the only requirement, there is nothing else to install:
  the project has no outside libraries.
- **An API key for an AI model.** The default is Google Gemini, which has a free tier:
  get a key at <https://aistudio.google.com/apikey>.

## Setting it up

1. Copy `.env.example` to a new file called `.env`.
2. Open `.env` and replace `YOUR_LOCAL_API_KEY_HERE` with your key (`LLM_API_KEY=...`).

`.env` is in `.gitignore`, so your key stays on your computer. Never commit it or paste it anywhere public.

## Running it

| | Windows | macOS / Linux |
|---|---|---|
| Window (GUI) | `run-gui.bat` | `./run-gui.sh` |
| Terminal (CLI) | `run.bat` | `./run.sh` |

These compile the code and start the app. Run them from the project folder so it can find your `.env`.
If you use Maven instead: `mvn package`, then `java -jar target/game-agent.jar` (CLI)
or `java -cp target/game-agent.jar gameagent.gui.GuiMain` (GUI).

Your profile and saved data go in a `data/` folder, which is also git-ignored.
No key? The app still starts, but anything that needs the AI will tell you so.

## What it can do

| Feature | In the window | In the terminal |
|---|---|---|
| Game recommendations + saved catalogue | Recommend tab | `/recommend`, `/catalog` |
| Your profile (likes, dislikes, notes) | Profile tab | `/profile`, `/like`, `/dislike`, `/note` |
| Rate games you've played | Feedback tab | `/rate` |
| Short interview to fill in your tastes | Feedback tab | `/refine` |
| Steam profile link | Steam tab | `/steam <profile link>` |
| Curveball roulette (one popular, one hidden gem) | Recommend tab | `/curveball` |
| Gaming news | News tab | `/news` |
| Gaming calendar | Calendar tab | `/calendar` |
| Friend recommendations | Friends tab | `/friends` |
| Playtime estimator | Playtime tab | `/playtime <game>` |
| Chat with the agent | Chat tab | just type |

Type `/help` in the terminal for every command.

## Example

```
you> I loved Hades and Dead Cells but I hate grinding. I only have about 45 minutes a night.
agent> Got it! Here are some picks that fit short, punchy sessions.
1. Hollow Knight  (match 88%) ...
you> /rate Hades | 5 | great combat, weak story
you> /curveball
you> /steam https://steamcommunity.com/id/yourname
you> /calendar when mon,wed 20:00-22:00; sat 14:00-18:00
you> /calendar make month mix
you> /playtime Hades
```

## Good to know

- **Steam:** the app reads your public profile page (no login or Steam key). Steam only shows a profile's most-played
  games that way, so that's all it can learn from you or your friends. Set your profile and game details to Public.
- **Playtime:** typical times come from IGDB if you add `IGDB_CLIENT_ID` and `IGDB_CLIENT_SECRET` to `.env`
  (free at <https://dev.twitch.tv/console>). Without them, the AI makes an estimate and says so.
- **Other AI models:** set `LLM_PROVIDER=openai` (plus model, key and `LLM_BASE_URL`) to use any OpenAI-compatible service.

## Tests

Run `test.bat` (Windows) or `./test.sh` (macOS / Linux). They work offline with fake servers and a fake AI,
so they don't use your key. They also include checks that keep the design patterns intact.

## How it's built

Both the GUI and CLI only talk to one class, `AgentController`, which passes work to the services.
Design patterns used: Facade, Strategy, Factory Method, Observer, Adapter, Decorator, Command and Null Object.
The full feature-to-class mapping is in the Stage 2 report.

## AI tools and models

- Built with Claude (Anthropic) as a coding assistant.
- The app itself uses Google Gemini by default (`gemini-3.7-flash`, with fallback models).
