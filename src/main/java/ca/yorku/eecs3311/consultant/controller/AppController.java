package ca.yorku.eecs3311.consultant.controller;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The FACADE of section 4 of the Stage 1 report: the one surface the GUI and
 * the CLI are allowed to touch.
 *
 * <p>Neither client holds domain logic. They collect input, call a method
 * here, and render what comes back — which is what makes "every feature works
 * from both the GUI and the CLI" achievable rather than two parallel
 * implementations that drift apart.
 *
 * <p>Methods arrive as the features behind them are built. The signatures in
 * the Stage 1 class diagram that have no implementation yet are deliberately
 * absent rather than present and throwing: a method that always fails is not
 * a facade, it is a placeholder.
 */
public class AppController {

    private final ProjectMemory memory;
    private final ToolManager tools;
    private final MemoryStore store;

    public AppController(ProjectMemory memory, ToolManager tools, MemoryStore store) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.memory = memory;
        this.tools = tools;
        this.store = store;
    }

    // --- F01: set up the project profile (UC01, SD01) -----------------------

    /**
     * Validates a profile, stores it, and writes it to disk.
     *
     * <p>The order matters and is the whole point of SD01: validation happens
     * here, before anything is stored, so a rejected profile cannot leave the
     * live project half-updated.
     *
     * @param profile the profile to save; must not be null
     * @return the stored profile
     * @throws ValidationException if a required field is empty — nothing is
     *     stored and the previous profile is untouched
     * @throws StorageException if the write fails after validation passed; the
     *     in-memory profile is left as it was
     */
    public ProjectProfile saveProfile(ProjectProfile profile)
            throws ValidationException, StorageException {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }

        List<String> missing = profile.missingFields();
        if (!missing.isEmpty()) {
            throw new ValidationException(missing);
        }

        // keep the old one so a failed write can be rolled back
        ProjectProfile previous = memory.getProfile();
        memory.saveProfile(profile);
        try {
            store.save(memory);
        } catch (IOException e) {
            memory.saveProfile(previous);
            throw new StorageException(
                    "the project could not be written to " + store.getFile(), e);
        }
        return memory.getProfile();
    }

    /** @return the current profile, empty but never null before the first save */
    public ProjectProfile getProfile() {
        return memory.getProfile();
    }

    // --- lifecycle -----------------------------------------------------------

    /**
     * Loads a stored project, if there is one.
     *
     * @return true when a project was loaded; false on a first run or when the
     *     file is unreadable, which is reported rather than thrown so the
     *     application always starts
     */
    public boolean loadStoredProject() {
        return store.load(memory);
    }

    /** @return the file the project is stored in, for the UI to show */
    public Path getStorageLocation() {
        return store.getFile();
    }

    /** @return the tool layer, for the agents this facade will hold. */
    protected ToolManager tools() {
        return tools;
    }
}
