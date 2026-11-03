package ca.yorku.eecs3311.consultant.context;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import java.io.IOException;
import java.nio.file.Path;

/**
 * One way of finding out what a researcher's project is currently doing — the
 * second TARGET of the Adapter pattern in section 4 of the Stage 1 report.
 *
 * <p>Two implementations with genuinely different sources: one reads the files
 * and Git history directly, the other asks a coding agent that has been working
 * in the directory. {@link #isAvailable(Path)} is what makes the choice a real
 * runtime decision rather than a hardcoded preference.
 */
public interface ProjectContextProvider {

    /**
     * @param projectPath the directory to inspect
     * @return true when this provider can say something useful about that
     *     directory. Must not throw and must not be slow: it is a probe.
     */
    boolean isAvailable(Path projectPath);

    /**
     * Reads the project's current state.
     *
     * @param projectPath the directory to inspect
     * @return a snapshot with {@code providerName} set to {@link #name()}
     * @throws IOException if the directory cannot be read at all — UC02 flow 1a,
     *     where nothing is stored
     */
    ContextSnapshot scan(Path projectPath) throws IOException;

    /** @return a short label, shown so the researcher knows where facts came from */
    String name();
}
