package ca.yorku.eecs3311.consultant.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@link ProjectMemory} as a JSON file, so a researcher's
 * project survives between runs.
 *
 * <p>Separated from the singleton so that persistence is a concern of this
 * class alone. Agents call {@code ProjectMemory}; only the application entry
 * points call this.
 */
public class MemoryStore {

    private static final String FILE_NAME = "project-memory.json";

    private final Path directory;
    private final ObjectMapper mapper;

    /**
     * @param directory where the memory file lives, created on first save
     */
    public MemoryStore(Path directory) {
        this.directory = directory;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                // a file written by a newer version must not break an older one
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Resolves the default location, {@code ~/.research-consultant}, unless
     * {@code RC_DATA_DIR} overrides it.
     *
     * <p>An absolute default matters: a relative path would resolve against the
     * working directory, which differs between launching the GUI and running
     * the CLI. The two would then keep separate files and silently disagree,
     * defeating the point of a single shared memory.
     *
     * @param override a configured directory, may be null or blank
     * @return the directory to store memory in
     */
    public static Path defaultDirectory(String override) {
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".research-consultant");
    }

    /** @return the full path of the memory file */
    public Path getFile() {
        return directory.resolve(FILE_NAME);
    }

    /** @return true when a memory file is already present */
    public boolean exists() {
        return Files.isRegularFile(getFile());
    }

    /**
     * Writes the current memory, creating the directory if needed.
     *
     * @param memory the live memory to persist
     * @throws IOException if the file cannot be written
     */
    public void save(ProjectMemory memory) throws IOException {
        Files.createDirectories(directory);
        MemoryState state = new MemoryState();
        state.setProfile(memory.getProfile());
        state.setPapers(memory.getPaperAnalyses());
        state.setLastSnapshot(memory.getLastSnapshot());
        mapper.writeValue(getFile().toFile(), state);
    }

    /**
     * Loads stored memory into the singleton, replacing what it holds.
     *
     * <p>A missing file is a normal first run. A corrupt one is reported by
     * returning false rather than thrown, because a hand-edited or truncated
     * file must not stop the application from starting — the researcher should
     * get an empty workspace and a warning, not a stack trace.
     *
     * @param memory the memory to populate
     * @return true when state was loaded, false when there was nothing usable
     */
    public boolean load(ProjectMemory memory) {
        if (!exists()) {
            return false;
        }
        try {
            MemoryState state = mapper.readValue(getFile().toFile(), MemoryState.class);
            memory.clear();
            memory.saveProfile(state.getProfile());
            for (var analysis : state.getPapers()) {
                memory.addPaperAnalysis(analysis);
            }
            if (state.getLastSnapshot() != null) {
                // absent means no import has ever happened; clear() already
                // left an empty snapshot in place, which is what F10 diffs against
                memory.saveSnapshot(state.getLastSnapshot());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
