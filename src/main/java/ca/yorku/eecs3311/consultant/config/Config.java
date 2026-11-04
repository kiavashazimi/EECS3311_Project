package ca.yorku.eecs3311.consultant.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application settings, read from a local {@code .env} file and the process
 * environment.
 *
 * <p>No credential is ever written in source. The real key lives in {@code .env},
 * which is git-ignored; {@code .env.example} holds placeholders only. An
 * environment variable takes precedence over the file, so the application can be
 * run without editing anything on disk.
 *
 * <p>{@link #toString()} deliberately never includes the key. A single careless
 * log line, screenshot or screen recording would otherwise expose it.
 */
public final class Config {

    /** The placeholder shipped in {@code .env.example}; not a usable key. */
    private static final String PLACEHOLDER = "YOUR_LOCAL_API_KEY_HERE";

    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";

    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = values;
    }

    /** @return configuration from {@code ./.env} and the process environment */
    public static Config load() {
        return load(Path.of(".env"), System.getenv());
    }

    /**
     * @param envFile     the file to read, may be absent
     * @param environment environment variables, which win over the file
     * @return the resolved configuration
     */
    public static Config load(Path envFile, Map<String, String> environment) {
        Map<String, String> merged = new HashMap<>(parse(envFile));
        if (environment != null) {
            environment.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    merged.put(k, v);
                }
            });
        }
        return new Config(merged);
    }

    /**
     * Reads {@code KEY=value} lines. Comments, blank lines and lines without a
     * separator are skipped rather than treated as errors — a hand-edited file
     * with one bad line should still yield the rest of its settings.
     */
    private static Map<String, String> parse(Path envFile) {
        Map<String, String> parsed = new HashMap<>();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return parsed;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile);
        } catch (IOException e) {
            return parsed;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = unquote(line.substring(eq + 1).strip());
            if (!key.isEmpty()) {
                parsed.put(key, value);
            }
        }
        return parsed;
    }

    private static String unquote(String v) {
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private String get(String key, String fallback) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? fallback : v.strip();
    }

    /**
     * @return true when a usable key is configured. The placeholder from
     *     {@code .env.example} counts as absent, so copying the example without
     *     editing it does not look like a configured key
     */
    public boolean hasLlmApiKey() {
        String key = get("LLM_API_KEY", "");
        return !key.isBlank() && !PLACEHOLDER.equals(key);
    }

    /**
     * @return the configured API key
     * @throws IllegalStateException with instructions, rather than returning
     *     null for a caller to trip over later
     */
    public String llmApiKey() {
        if (!hasLlmApiKey()) {
            throw new IllegalStateException(
                    "No LLM_API_KEY configured. Copy .env.example to .env and set LLM_API_KEY, "
                            + "or export LLM_API_KEY in your environment.");
        }
        return get("LLM_API_KEY", "");
    }

    /**
     * @return true when a Semantic Scholar key is configured. The placeholder
     *     from {@code .env.example} counts as absent, so copying the example
     *     without editing it does not look like a configured key
     */
    public boolean hasSemanticScholarKey() {
        String key = get("S2_API_KEY", "");
        return !key.isBlank() && !PLACEHOLDER.equals(key) && !"YOUR_S2_KEY_HERE".equals(key);
    }

    /**
     * @return the Semantic Scholar key, or blank when none is set. Blank is
     *     usable — the endpoint works anonymously, just rate limited hard —
     *     so unlike the LLM key this does not throw
     */
    public String semanticScholarKey() {
        return get("S2_API_KEY", "");
    }

    public String llmModel() {
        return get("LLM_MODEL", DEFAULT_MODEL);
    }

    public String llmBaseUrl() {
        return get("LLM_BASE_URL", DEFAULT_BASE_URL);
    }

    /**
     * @return where project memory is stored. Always absolute: a relative path
     *     would resolve differently for the GUI and the CLI, which would then
     *     keep separate files
     */
    public Path dataDir() {
        String configured = get("RC_DATA_DIR", "");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".research-consultant");
    }

    /** @return a description that states whether a key is set, never the key */
    @Override
    public String toString() {
        return "Config{llmApiKey=" + (hasLlmApiKey() ? "set" : "not set")
                + ", llmModel=" + llmModel()
                + ", llmBaseUrl=" + llmBaseUrl()
                + ", dataDir=" + dataDir() + "}";
    }
}
