package gameagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import gameagent.agent.AgentController;
import gameagent.cli.CliApp;
import gameagent.config.AppConfig;

/** Entry point for the command-line version. */
public class Main {
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        AppConfig config = AppConfig.load(Path.of(".env"));
        AgentController agent = AppContext.fromConfig(config, out::println);
        new CliApp(agent, in, out).run();
    }
}
