package ca.yorku.eecs3311.consultant.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.RepoMetadata;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing driven by canned GitHub API bodies. No network. */
class GitHubClientTest {

    private static final String REPO_BODY = """
            {"name":"xrag","full_name":"example/xrag",
             "license":{"spdx_id":"MIT","name":"MIT License"},
             "default_branch":"main"}
            """;

    private static final String CONTENTS_BODY = """
            [{"name":"README.md","type":"file"},
             {"name":"train.py","type":"file"},
             {"name":"evaluate.py","type":"file"},
             {"name":"data","type":"dir"},
             {"name":"LICENSE","type":"file"}]
            """;

    /** Answers the repository call and the contents call separately. */
    private static GitHubClient clientReturning(String repoBody, String contentsBody) {
        return new GitHubClient(url ->
                url.endsWith("/contents/") ? contentsBody : repoBody);
    }

    @Test
    void readsTheLicenceAndTheTopLevelListing() throws Exception {
        RepoMetadata m = clientReturning(REPO_BODY, CONTENTS_BODY)
                .fetchRepo("https://github.com/example/xrag");

        assertEquals("MIT", m.getLicense());
        assertTrue(m.hasLicense());
        assertEquals(5, m.getTopLevelFiles().size());
        assertTrue(m.hasTopLevelEntry("train.py"));
    }

    @Test
    void normalisesTheUrlItWasGiven() throws Exception {
        for (String written : List.of(
                "https://github.com/example/xrag",
                "https://github.com/example/xrag.git",
                "http://www.github.com/example/xrag/",
                "github.com/example/xrag",
                "https://github.com/example/xrag/tree/main/configs")) {
            RepoMetadata m = clientReturning(REPO_BODY, CONTENTS_BODY).fetchRepo(written);
            assertEquals("https://github.com/example/xrag", m.getUrl(), "from: " + written);
        }
    }

    @Test
    void aRepositoryWithNoLicenceIsNotClaimedToHaveOne() throws Exception {
        RepoMetadata m = clientReturning("{\"name\":\"x\",\"license\":null}", CONTENTS_BODY)
                .fetchRepo("https://github.com/example/x");

        assertFalse(m.hasLicense());
    }

    @Test
    void makesExactlyTwoApiCalls() throws Exception {
        // the rate limit is 60/hour without a token, so each call counts
        List<String> urls = new ArrayList<>();
        new GitHubClient(url -> {
            urls.add(url);
            return url.endsWith("/contents/") ? CONTENTS_BODY : REPO_BODY;
        }).fetchRepo("https://github.com/example/xrag");

        assertEquals(2, urls.size(), urls.toString());
        assertTrue(urls.get(0).endsWith("/repos/example/xrag"), urls.get(0));
        assertTrue(urls.get(1).endsWith("/contents/"), urls.get(1));
    }

    // --- the failures UC08 3a requires naming -------------------------------

    @Test
    void aMissingRepositoryIsReportedWithItsUrl() {
        GitHubClient client = new GitHubClient(url -> null);

        RepositoryUnavailableException e = assertThrows(RepositoryUnavailableException.class,
                () -> client.fetchRepo("https://github.com/example/gone"));
        assertTrue(e.getMessage().contains("example/gone"), e.getMessage());
        assertTrue(e.getMessage().contains("private"), "the likely reason should be stated");
    }

    @Test
    void aRateLimitIsReportedAsItsOwnReason() {
        GitHubClient client = new GitHubClient(url -> {
            throw new IOException("rate-limited by GitHub (60 requests/hour without a token)");
        });

        RepositoryUnavailableException e = assertThrows(RepositoryUnavailableException.class,
                () -> client.fetchRepo("https://github.com/example/xrag"));
        assertTrue(e.getMessage().contains("rate-limited"), e.getMessage());
    }

    @Test
    void aNonGitHubUrlIsRefusedPlainly() {
        GitHubClient client = clientReturning(REPO_BODY, CONTENTS_BODY);

        RepositoryUnavailableException e = assertThrows(RepositoryUnavailableException.class,
                () -> client.fetchRepo("https://gitlab.com/example/xrag"));
        assertTrue(e.getMessage().contains("not a GitHub repository"), e.getMessage());
    }

    @Test
    void aMissingUrlIsRefused() {
        GitHubClient client = clientReturning(REPO_BODY, CONTENTS_BODY);
        assertThrows(RepositoryUnavailableException.class, () -> client.fetchRepo(null));
        assertThrows(RepositoryUnavailableException.class, () -> client.fetchRepo("  "));
    }

    @Test
    void anUnreadableListingLeavesTheMetadataRatherThanFailing() throws Exception {
        // the repository exists; only its listing could not be parsed
        RepoMetadata m = clientReturning(REPO_BODY, "not json").fetchRepo(
                "https://github.com/example/xrag");

        assertEquals("MIT", m.getLicense());
        assertTrue(m.getTopLevelFiles().isEmpty());
    }

    @Test
    void anUnreadableRepositoryBodyIsReported() {
        GitHubClient client = clientReturning("not json", CONTENTS_BODY);
        assertThrows(RepositoryUnavailableException.class,
                () -> client.fetchRepo("https://github.com/example/xrag"));
    }
}
