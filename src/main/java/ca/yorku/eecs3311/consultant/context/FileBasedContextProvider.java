package ca.yorku.eecs3311.consultant.context;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An ADAPTER over a project directory: reads Git history, configuration files
 * and result files directly.
 *
 * <p>This is the implementation committed for Stage 2, and the fallback
 * whenever no coding-agent context is present. It is always available for a
 * readable directory, which is what makes it a safe last resort.
 */
public class FileBasedContextProvider implements ProjectContextProvider {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private static final int MAX_COMMITS = 10;
    private static final int MAX_FILES = 25;
    private static final int MAX_VALUE_CHARS = 120;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> CONFIG_SUFFIXES =
            List.of(".yaml", ".yml", ".json", ".toml", ".ini", ".cfg");
    private static final List<String> RESULT_SUFFIXES =
            List.of(".csv", ".tsv", ".log", ".out", ".txt");

    /**
     * Files whose contents must never be read.
     *
     * <p>A config directory routinely holds credentials, and anything read here
     * is sent to a language model for summarising. Skipping by name is crude but
     * it is the check that stops the obvious disaster.
     */
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(secret|credential|password|passwd|token|apikey|api_key).*|^\\.env.*");

    /** Anything shaped like a key, redacted from a value that is read. */
    private static final Pattern SECRET_VALUE =
            Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{10,}"
                    + "|[A-Za-z0-9+/]{40,}={0,2})");

    @Override
    public String name() {
        return "FileBasedContextProvider";
    }

    /** Always available for a readable directory: it is the fallback. */
    @Override
    public boolean isAvailable(Path projectPath) {
        return projectPath != null && Files.isDirectory(projectPath)
                && Files.isReadable(projectPath);
    }

    @Override
    public ContextSnapshot scan(Path projectPath) throws IOException {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            // UC02 1a: reported, and nothing is stored
            throw new IOException("not a readable directory: " + projectPath);
        }

        ContextSnapshot snapshot = new ContextSnapshot(
                LocalDateTime.now().format(STAMP), projectPath.toAbsolutePath().toString());
        snapshot.setProviderName(name());
        snapshot.setRecentCommits(readCommits(projectPath));

        // built locally: the snapshot's getters hand back unmodifiable views,
        // which is the right behaviour for a persisted value type
        Map<String, String> configs = new LinkedHashMap<>();
        List<String> results = new ArrayList<>();

        try (Stream<Path> entries = Files.list(projectPath)) {
            List<Path> files = entries.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (SENSITIVE_NAME.matcher(fileName).matches()) {
                    continue;
                }
                if (hasSuffix(fileName, CONFIG_SUFFIXES) && configs.size() < MAX_FILES) {
                    configs.put(fileName, describe(file));
                } else if (hasSuffix(fileName, RESULT_SUFFIXES) && results.size() < MAX_FILES) {
                    results.add(fileName);
                }
            }
        }
        snapshot.setConfigEntries(configs);
        snapshot.setResultFiles(results);
        return snapshot;
    }

    private static boolean hasSuffix(String fileName, List<String> suffixes) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(lower::endsWith);
    }

    /** Matches a top-level `key:` or `key =` at the start of a line. */
    private static final Pattern TOP_LEVEL_KEY =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*[:=]");

    /**
     * A one-line descriptor of a config file: its top-level keys when it has
     * any, otherwise its size and first meaningful line.
     *
     * <p>Keys rather than values, for two reasons. A diff of line counts says
     * only that something changed — the first live run of F10 reported
     * "2 lines: model: tcn -> 3 lines: model: tcn" and the summary admitted
     * the specifics were not clear. A diff of keys says *what* changed: a new
     * {@code dropout} key appears. And a key name is far less likely than a
     * value to be a credential, so listing keys also leaks less.
     */
    private static String describe(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> keys = new ArrayList<>();
            String firstMeaningful = "";

            for (String raw : lines) {
                if (raw.isBlank() || raw.strip().startsWith("#")) {
                    continue;
                }
                if (firstMeaningful.isEmpty()) {
                    firstMeaningful = raw.strip();
                }
                Matcher key = TOP_LEVEL_KEY.matcher(raw);
                if (key.find() && !keys.contains(key.group(1))) {
                    keys.add(key.group(1));
                }
            }

            String value = keys.size() >= 2
                    ? "keys: " + String.join(", ", keys)
                    : lines.size() + " lines"
                            + (firstMeaningful.isBlank() ? "" : ": " + redact(firstMeaningful));
            return value.length() > MAX_VALUE_CHARS
                    ? value.substring(0, MAX_VALUE_CHARS - 3) + "..."
                    : redact(value);
        } catch (IOException | RuntimeException e) {
            return "unreadable";
        }
    }

    /** Masks anything key-shaped before it can reach a prompt or a log. */
    static String redact(String value) {
        return value == null ? "" : SECRET_VALUE.matcher(value).replaceAll("[REDACTED]");
    }

    /**
     * Recent commit subjects, or an empty list when this is not a Git
     * repository.
     *
     * <p>UC02 flow 4a: a project that is not under version control is normal
     * and must not fail the import, so a missing or failing {@code git} is
     * swallowed rather than propagated.
     */
    private static List<String> readCommits(Path projectPath) {
        if (!Files.isDirectory(projectPath.resolve(".git"))) {
            return List.of();
        }
        List<String> commits = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("git", "log", "--oneline", "-" + MAX_COMMITS)
                    .directory(projectPath.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null && commits.size() < MAX_COMMITS) {
                    if (!line.isBlank()) {
                        commits.add(line.strip());
                    }
                }
            }
            if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            return process.exitValue() == 0 ? commits : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
