package ca.yorku.eecs3311.consultant.context;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * An ADAPTER over a coding agent's own notes about a project.
 *
 * <p>Reads what Claude Code leaves on disk — a {@code CLAUDE.md} describing the
 * project, and a {@code .claude/} directory — rather than talking to a running
 * session. That matters: inter-process messaging would make the feature depend
 * on a session being live, while a file is there whether or not anyone is
 * working right now.
 *
 * <p>Why this provider exists at all: a reconstruction of one real project's
 * aims from its filenames and commit messages got several things wrong that its
 * {@code CLAUDE.md} got right, including which dataset was training data and
 * which was the held-out test set. Inferring a project's intent from its files
 * produces confident, wrong answers; the agent's own description does not.
 */
public class ClaudeCodeContextProvider implements ProjectContextProvider {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Beyond this the description crowds out everything else in the prompt. */
    static final int MAX_DESCRIPTION_CHARS = 6000;

    @Override
    public String name() {
        return "ClaudeCodeContextProvider";
    }

    @Override
    public boolean isAvailable(Path projectPath) {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            return false;
        }
        return Files.isRegularFile(projectPath.resolve("CLAUDE.md"))
                || Files.isDirectory(projectPath.resolve(".claude"));
    }

    @Override
    public ContextSnapshot scan(Path projectPath) throws IOException {
        if (!isAvailable(projectPath)) {
            throw new IOException("no coding-agent context in " + projectPath);
        }

        ContextSnapshot snapshot = new ContextSnapshot(
                LocalDateTime.now().format(STAMP), projectPath.toAbsolutePath().toString());
        snapshot.setProviderName(name());

        Path claudeMd = projectPath.resolve("CLAUDE.md");
        if (Files.isRegularFile(claudeMd)) {
            String description = Files.readString(claudeMd).strip();
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                description = description.substring(0, MAX_DESCRIPTION_CHARS) + "\n[truncated]";
            }
            snapshot.setAgentDescription(description);
        }
        return snapshot;
    }
}
