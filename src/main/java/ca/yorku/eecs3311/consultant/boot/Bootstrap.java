package ca.yorku.eecs3311.consultant.boot;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.config.Config;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.literature.ArxivSource;
import ca.yorku.eecs3311.consultant.literature.SemanticScholarSource;
import ca.yorku.eecs3311.consultant.llm.ClaudeClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;

/**
 * The composition root: the one place that chooses the real adapters and hands
 * the clients a controller that is ready to use.
 *
 * <p>Both clients used to build this graph themselves, and they drifted.
 * {@code Cli.main} called {@code loadStoredProject()}; {@code App.start} did
 * not. The GUI therefore opened with an empty profile and no analyses — so
 * "Discover papers" could not run at all — and because a save writes the whole
 * memory, the first save from the GUI replaced every stored analysis with
 * nothing. Neither client reported anything.
 *
 * <p>The fix is structural rather than a third copy of the same four lines: a
 * step a client never performs is a step it cannot forget. {@link MemoryStore}
 * refuses to write a file it has never read, so the same mistake cannot be
 * reintroduced here or anywhere else.
 *
 * <p>This class is deliberately outside {@code controller}: choosing
 * {@link ClaudeClient} and {@link ArxivSource} is a wiring decision, and
 * keeping it here is what lets {@code AppController} stay ignorant of which
 * LLM or literature source it is talking to — the point of the Adapter and
 * Strategy patterns it sits behind.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /** @return a controller wired to the real services, from the real config */
    public static AppController application() {
        return application(Config.load());
    }

    /**
     * @param config where the key, the model and the data directory come from
     * @return a controller wired to the real services, with any stored project
     *     already loaded. A missing or unusable file is a normal first run and
     *     leaves an empty workspace rather than failing
     */
    public static AppController application(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        ToolManager tools = new ToolManager(new ClaudeClient(config));
        chooseLiteratureSources(tools, config);

        AppController controller = new AppController(
                ProjectMemory.getInstance(), tools, new MemoryStore(config.dataDir()));
        controller.loadStoredProject();
        return controller;
    }

    /**
     * @param config the configuration to read
     * @return the name of the source that would lead, so a client or a test can
     *     report which Strategy is in use without reaching into the wiring
     */
    public static String literatureSourceName(Config config) {
        return config.hasSemanticScholarKey() ? "Semantic Scholar" : "arXiv";
    }

    /**
     * @param config the configuration to read
     * @return the name of the source that would be fallen back to, or null when
     *     there is only one
     */
    public static String fallbackSourceName(Config config) {
        return config.hasSemanticScholarKey() ? "arXiv" : null;
    }

    /**
     * Picks the literature Strategy, and the one to fall back to.
     *
     * <p>With an {@code S2_API_KEY} set, Semantic Scholar leads and arXiv
     * catches it: S2 ranks a whole research question by relevance, which arXiv
     * cannot do because its terms are ANDed, but S2 rate limits hard enough
     * that HTTP 429 is its ordinary failure rather than an exceptional one.
     *
     * <p>Without a key, arXiv is used alone. The anonymous S2 endpoint returned
     * 429 on every attempt from the development machine, so leading with it
     * would buy a guaranteed failed round trip before every single search.
     *
     * <p>This is the Strategy being chosen by configuration, and
     * {@code ToolManager} then switching at runtime when the choice fails.
     * No agent is involved in either decision.
     */
    private static void chooseLiteratureSources(ToolManager tools, Config config) {
        if (config.hasSemanticScholarKey()) {
            tools.setLiteratureSource(
                    new SemanticScholarSource(config.semanticScholarKey()));
            tools.setFallbackLiteratureSource(new ArxivSource());
        } else {
            tools.setLiteratureSource(new ArxivSource());
        }
    }
}
