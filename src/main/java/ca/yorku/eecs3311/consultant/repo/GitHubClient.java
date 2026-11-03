package ca.yorku.eecs3311.consultant.repo;

import ca.yorku.eecs3311.consultant.domain.RepoMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a GitHub repository's **metadata**. Nothing is cloned, downloaded or
 * executed.
 *
 * <p>That restriction is a project rule, not a limitation: a paper's repository
 * is arbitrary third-party code, and a tool that ran it to judge
 * reproducibility would be a remote-code-execution hole pointed at the
 * researcher's own machine. Two API calls — the repository record and its
 * top-level listing — answer "does the code exist and is it reusable" without
 * any of that risk.
 */
public class GitHubClient {

    private static final String API = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ENTRIES = 60;

    /** Accepts the shapes a paper actually links: with or without .git, /tree/..., trailing /. */
    private static final Pattern OWNER_REPO = Pattern.compile(
            "(?i)^(?:https?://)?(?:www\\.)?github\\.com/"
                    + "([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/.*)?$");

    /**
     * Where a response body comes from — the transport seam.
     *
     * <p>Public so a caller can supply a different transport: a cache in front
     * of the 60-requests-per-hour limit, or a fake in a test that must not
     * depend on a repository still existing on github.com.
     */
    public interface ApiFetcher {
        /**
         * @param url the API endpoint to read
         * @return the body, or null when the resource is absent (HTTP 404)
         * @throws IOException if it cannot be reached
         */
        String get(String url) throws IOException;
    }

    private final ApiFetcher fetcher;

    public GitHubClient() {
        this((String) null);
    }

    /** @param token an optional GitHub token; raises the rate limit from 60/hour */
    public GitHubClient(String token) {
        this.fetcher = url -> httpGet(url, token);
    }

    /** @param fetcher the transport to use instead of the built-in HTTP one */
    public GitHubClient(ApiFetcher fetcher) {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        this.fetcher = fetcher;
    }

    /**
     * @param repoUrl a GitHub repository URL
     * @return its metadata
     * @throws RepositoryUnavailableException if the URL is not a GitHub
     *     repository, or the repository cannot be read, with the reason
     */
    public RepoMetadata fetchRepo(String repoUrl) throws RepositoryUnavailableException {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new RepositoryUnavailableException("no repository URL given");
        }
        Matcher parsed = OWNER_REPO.matcher(repoUrl.strip());
        if (!parsed.matches()) {
            // only GitHub is supported; say so rather than failing obscurely
            throw new RepositoryUnavailableException(
                    "not a GitHub repository URL: " + repoUrl.strip());
        }
        String owner = parsed.group(1);
        String repo = parsed.group(2);

        RepoMetadata metadata = new RepoMetadata("https://github.com/" + owner + "/" + repo);
        readRepository(owner, repo, metadata);
        metadata.setTopLevelFiles(readTopLevel(owner, repo));
        return metadata;
    }

    private void readRepository(String owner, String repo, RepoMetadata metadata)
            throws RepositoryUnavailableException {
        String body = request(API + "/repos/" + owner + "/" + repo);
        if (body == null) {
            throw new RepositoryUnavailableException(
                    "repository not found, or private: " + metadata.getUrl());
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode license = node.path("license");
            if (!license.isMissingNode() && !license.isNull()) {
                metadata.setLicense(license.path("spdx_id").asText(
                        license.path("name").asText("")));
            }
        } catch (IOException e) {
            throw new RepositoryUnavailableException(
                    "the GitHub reply could not be read: " + e.getMessage(), e);
        }
    }

    /** The top-level listing, which is what tells us whether code is there. */
    private List<String> readTopLevel(String owner, String repo)
            throws RepositoryUnavailableException {
        String body = request(API + "/repos/" + owner + "/" + repo + "/contents/");
        if (body == null) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        try {
            for (JsonNode item : MAPPER.readTree(body)) {
                String name = item.path("name").asText("");
                if (!name.isBlank() && entries.size() < MAX_ENTRIES) {
                    entries.add(name);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return entries;
    }

    private String request(String url) throws RepositoryUnavailableException {
        try {
            return fetcher.get(url);
        } catch (IOException e) {
            throw new RepositoryUnavailableException(
                    "GitHub could not be reached: " + e.getMessage(), e);
        }
    }

    private static String httpGet(String url, String token) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "research-consultant/0.1 (EECS3311 course project)")
                .GET();
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> response.body();
                case 404 -> null;
                case 403, 429 -> throw new IOException(
                        "rate-limited by GitHub (60 requests/hour without a token)");
                default -> throw new IOException("GitHub returned HTTP " + response.statusCode());
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the GitHub request was interrupted", e);
        }
    }
}
