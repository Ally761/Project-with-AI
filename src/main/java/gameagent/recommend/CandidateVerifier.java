package gameagent.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import gameagent.data.GameDataSource;
import gameagent.model.Game;

/**
 * Grounding step: checks every title the LLM proposed against the real game catalogue (in parallel),
 * so hallucinated games are dropped and real ones get genres, price and a store link.
 */
final class CandidateVerifier {
    record Outcome(List<Game> verified, List<Game> unverified, boolean sourceDown) {}

    private final GameDataSource data;

    CandidateVerifier(GameDataSource data) {
        this.data = data;
    }

    Outcome verify(List<String> names) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Map<String, Future<Optional<Game>>> futures = new LinkedHashMap<>();
            for (String n : names) futures.put(n, pool.submit(() -> data.findByName(n)));
            List<Game> verified = new ArrayList<>();
            List<Game> unverified = new ArrayList<>();
            int failures = 0;
            for (Map.Entry<String, Future<Optional<Game>>> e : futures.entrySet()) {
                try {
                    Optional<Game> g = e.getValue().get();
                    if (g.isPresent()) verified.add(g.get());
                    else unverified.add(Game.unverified(e.getKey()));
                } catch (Exception ex) {
                    failures++;
                    unverified.add(Game.unverified(e.getKey()));
                }
            }
            boolean down = !names.isEmpty() && failures == names.size();
            return new Outcome(verified, unverified, down);
        } finally {
            pool.shutdownNow();
        }
    }
}
