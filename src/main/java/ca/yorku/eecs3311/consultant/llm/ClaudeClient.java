package ca.yorku.eecs3311.consultant.llm;

import ca.yorku.eecs3311.consultant.config.Config;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * The ADAPTER: translates Anthropic's Java SDK into the single
 * {@link LLMClient#generate(String)} call the agents expect.
 *
 * <p>Everything provider-specific stops here — the SDK's builders, its request
 * and response types, its exception hierarchy, the model id and the token
 * limit. Agents see a String in and a String out.
 */
public final class ClaudeClient implements LLMClient {

    /** Enough for an analysis or a comparison narrative without truncating. */
    /**
     * The output budget for one call.
     *
     * <p>Measured, not guessed. Ranking 25 papers needs about 2,900 output
     * tokens — one JSON entry per paper, each with an id and a one-line reason.
     * At the previous 2,048 the reply was cut off mid-string, the JSON failed
     * to parse, and discovery reported every paper as unranked. It only
     * surfaced once Semantic Scholar was added, because its non-arXiv ids are
     * 40-character hashes rather than short arXiv ids, but 2,048 had never been
     * enough for a full ranking.
     */
    static final long DEFAULT_MAX_TOKENS = 4096L;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RETRIES = 2;

    private final Config config;
    private final long maxTokens;

    /** Built on first use, so an app with no key still starts. */
    private AnthropicClient sdk;

    public ClaudeClient(Config config) {
        this(config, DEFAULT_MAX_TOKENS);
    }

    public ClaudeClient(Config config, long maxTokens) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive, was " + maxTokens);
        }
        this.config = config;
        this.maxTokens = maxTokens;
    }

    @Override
    public String generate(String prompt) throws LLMUnavailableException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        if (!config.hasLlmApiKey()) {
            throw new LLMUnavailableException(
                    "No LLM_API_KEY configured. Copy .env.example to .env and set LLM_API_KEY, "
                            + "or export LLM_API_KEY in your environment.");
        }

        Message reply;
        try {
            reply = client().messages().create(MessageCreateParams.builder()
                    .model(config.llmModel())
                    .maxTokens(maxTokens)
                    .addUserMessage(prompt)
                    .build());
        } catch (RuntimeException e) {
            // every provider failure becomes one failure mode for callers:
            // timeouts, rate limits, bad keys and 500s all land here
            throw new LLMUnavailableException(
                    "the Claude API call failed: " + scrub(e.getMessage()), e);
        }

        String text = textOf(reply);
        if (text.isBlank()) {
            throw new LLMUnavailableException("the model returned no text");
        }
        return text;
    }

    private AnthropicClient client() {
        if (sdk == null) {
            // LLM_BASE_URL is deliberately not passed: the SDK owns the
            // endpoint path, and Config holds the full .../v1/messages URL
            sdk = AnthropicOkHttpClient.builder()
                    .apiKey(config.llmApiKey())
                    .timeout(TIMEOUT)
                    .maxRetries(MAX_RETRIES)
                    .build();
        }
        return sdk;
    }

    /** Joins the text blocks of a reply, ignoring any non-text block. */
    private static String textOf(Message reply) {
        return reply.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining("\n"))
                .trim();
    }

    /**
     * Masks anything shaped like an API key. Provider error text is copied into
     * our own exception message, which may be logged or shown on screen during
     * a demo, so it is scrubbed before it can get there.
     */
    static String scrub(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("sk-ant-[A-Za-z0-9_-]+", "sk-ant-***");
    }
}
