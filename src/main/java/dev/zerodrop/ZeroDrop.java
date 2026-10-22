package dev.zerodrop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZeroDrop — disposable email inboxes for testing auth flows in CI.
 * OTPs and magic links auto-extracted at Cloudflare's edge.
 * No regex, no Docker, no signup.
 *
 * <pre>{@code
 * ZeroDrop mail = ZeroDrop.create();
 * String inbox = mail.generateInbox();
 *
 * // Trigger your app's email flow with the inbox address...
 *
 * Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));
 * email.otp();       // Optional["847291"]
 * email.magicLink(); // Optional["https://..."]
 * }</pre>
 */
public final class ZeroDrop {
    /** The SDK version, sent in the User-Agent header. */
    public static final String VERSION = "0.2.0";

    private static final String DEFAULT_BASE_URL = "https://zerodrop.dev";
    private static final String FREE_DOMAIN = "zerodrop-sandbox.online";
    private static final String[] ADJECTIVES = {
        "swift", "dark", "cold", "null", "void",
        "zero", "dead", "raw", "base", "core"
    };
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    // A free key from https://zerodrop.dev/keys: zd_<prefix>_<32 letters/digits>.
    // Inboxes named <prefix>-... can be read only with that key.
    private static final Pattern KEY_FORMAT = Pattern.compile("^zd_([a-z0-9]{4})_[A-Za-z0-9]{32}$");
    private static final Pattern PLAIN_TEXT = Pattern.compile(
        "Content-Type: text/plain[^\\r\\n]*\\r\\n\\r\\n(.*?)(?:\\r\\n--|\\r\\n\\r\\n--)",
        Pattern.DOTALL
    );

    private final String apiKey;
    private final String prefix;
    private final String baseUrl;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();

    private ZeroDrop(String apiKey, String baseUrl, HttpClient http) {
        String key = trimKey(apiKey);
        if (key.isEmpty()) {
            key = trimKey(System.getenv("ZERODROP_API_KEY"));
        }
        // A key goes into an HTTP header, so after trimming it may hold only
        // visible ASCII. Anything else is refused without echoing the key.
        if (!key.chars().allMatch(ch -> ch >= 0x21 && ch <= 0x7e)) {
            throw new IllegalArgumentException(
                "ZeroDrop: the API key contains a character that can't be sent in an HTTP header, "
                    + "such as a line break or a byte-order mark. Copy it again from https://zerodrop.dev/keys.");
        }
        this.apiKey = key.isEmpty() ? null : key;
        Matcher m = KEY_FORMAT.matcher(key);
        this.prefix = this.apiKey != null && m.matches() ? m.group(1) : null;
        this.baseUrl = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        this.http = http;
    }

    // Drops whitespace around a key, including the no-break spaces and
    // byte-order marks that copying and pasting can bring along.
    private static String trimKey(String key) {
        if (key == null) {
            return "";
        }
        int start = 0;
        int end = key.length();
        while (start < end && isKeyPadding(key.charAt(start))) {
            start++;
        }
        while (end > start && isKeyPadding(key.charAt(end - 1))) {
            end--;
        }
        return key.substring(start, end);
    }

    private static boolean isKeyPadding(char ch) {
        return ch <= ' ' || Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == '\uFEFF';
    }

    /**
     * Create a client. It uses ZERODROP_API_KEY from the environment when set;
     * otherwise it runs in free sandbox mode, where anyone who knows an inbox's
     * name can read it.
     *
     * @throws IllegalArgumentException if the key holds a character that can't
     *     be sent in an HTTP header (a line break, a byte-order mark...)
     */
    public static ZeroDrop create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Your key's inbox prefix ("k7x2"), or empty without a key. Inboxes under
     * it can be read only with that key.
     */
    public Optional<String> prefix() {
        return Optional.ofNullable(prefix);
    }

    /**
     * Returns a ready-to-use email address instantly.
     * No network request is made. With a key, the name starts with your
     * prefix: k7x2-swift-x7k29ab@zerodrop-sandbox.online.
     */
    public String generateInbox() {
        String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
        StringBuilder suffix = new StringBuilder(7);
        for (int i = 0; i < 7; i++) {
            suffix.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
        }
        String name = adjective + "-" + suffix;
        if (prefix != null) {
            name = prefix + "-" + name;
        }
        return name + "@" + FREE_DOMAIN;
    }

    /**
     * Returns the newest email in the inbox, or empty if none.
     */
    public Optional<Email> fetchLatest(String inbox) {
        return fetchLatest(inbox, null);
    }

    /**
     * Returns the newest email matching the filter, or empty if none match.
     *
     * @throws ZeroDropException.Auth    when the inbox needs another key; the message says why
     * @throws ZeroDropException.Network on transport failure
     */
    public Optional<Email> fetchLatest(String inbox, Filter filter) {
        String name = inboxName(inbox);
        String url = baseUrl + "/api/inbox/" + name + "?source=java-sdk";

        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "zerodrop-java/" + VERSION)
            .GET();
        if (apiKey != null) {
            req.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ZeroDropException.Network(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZeroDropException.Network("Request interrupted", e);
        }

        if (res.statusCode() == 401) {
            throw new ZeroDropException.Auth(errorReason(res.body()));
        }
        if (res.statusCode() != 200) {
            throw new ZeroDropException.Network("API returned " + res.statusCode());
        }

        JsonObject payload = gson.fromJson(res.body(), JsonObject.class);
        if (payload == null || !payload.has("emails")) {
            return Optional.empty();
        }

        JsonArray emails = payload.getAsJsonArray("emails");
        for (int i = 0; i < emails.size(); i++) {
            Email email = toEmail(emails.get(i).getAsJsonObject());
            if (filter == null || filter.matches(email)) {
                return Optional.of(email);
            }
        }
        return Optional.empty();
    }

    /**
     * Blocks until an email arrives, up to the given timeout.
     *
     * @throws ZeroDropException.Timeout if nothing arrives in time
     */
    public Email waitForLatest(String inbox, Duration timeout) {
        return waitForLatest(inbox, timeout, null);
    }

    /** Blocks with the default 10-second timeout. */
    public Email waitForLatest(String inbox) {
        return waitForLatest(inbox, DEFAULT_TIMEOUT, null);
    }

    /**
     * Blocks until an email matching the filter arrives.
     *
     * @throws ZeroDropException.Timeout if nothing arrives in time
     * @throws ZeroDropException.Auth    when the inbox needs another key; the message says why
     */
    public Email waitForLatest(String inbox, Duration timeout, Filter filter) {
        Instant deadline = Instant.now().plus(timeout);

        while (true) {
            Optional<Email> email = fetchLatest(inbox, filter);
            if (email.isPresent()) {
                return email.get();
            }

            if (Instant.now().plus(POLL_INTERVAL).isAfter(deadline)) {
                throw new ZeroDropException.Timeout(inbox, timeout.toSeconds());
            }

            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ZeroDropException.Timeout(inbox, timeout.toSeconds());
            }
        }
    }

    // The server explains a 401 in its JSON body; pass that on.
    private String errorReason(String body) {
        try {
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            if (obj != null && obj.has("error") && obj.get("error").isJsonPrimitive()) {
                return obj.get("error").getAsString();
            }
        } catch (RuntimeException ignored) {
            // not JSON: fall back to the generic message
        }
        return null;
    }

    private Email toEmail(JsonObject raw) {
        String rawBody = str(raw, "raw");
        Instant receivedAt = null;
        String receivedStr = str(raw, "receivedAt");
        if (!receivedStr.isEmpty()) {
            try {
                receivedAt = Instant.parse(receivedStr);
            } catch (Exception ignored) {
                // leave null
            }
        }

        String otp = str(raw, "otp");
        String magicLink = str(raw, "magicLink");

        return new Email(
            str(raw, "id"),
            str(raw, "from"),
            str(raw, "to"),
            str(raw, "subject"),
            extractBody(rawBody),
            rawBody,
            receivedAt,
            otp.isEmpty() ? null : otp,
            magicLink.isEmpty() ? null : magicLink
        );
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
            ? obj.get(key).getAsString()
            : "";
    }

    private static String extractBody(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Matcher m = PLAIN_TEXT.matcher(raw);
        if (m.find()) {
            return m.group(1).trim();
        }
        int idx = raw.indexOf("\r\n\r\n");
        if (idx >= 0) {
            String body = raw.substring(idx + 4).trim();
            return body.length() > 5000 ? body.substring(0, 5000) : body;
        }
        return "";
    }

    private static String inboxName(String inbox) {
        int at = inbox.indexOf('@');
        String name = at >= 0 ? inbox.substring(0, at) : inbox;
        return name.toLowerCase(Locale.ROOT);
    }

    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient http;

        /**
         * Your API key, free from https://zerodrop.dev/keys. Without it the
         * client reads ZERODROP_API_KEY from the environment.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Override for self-hosted instances. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Custom HttpClient (proxies, custom timeouts, etc.). */
        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /**
         * Builds the client.
         *
         * @throws IllegalArgumentException if the key holds a character that
         *     can't be sent in an HTTP header (a line break, a byte-order mark...)
         */
        public ZeroDrop build() {
            HttpClient client = http != null ? http : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            return new ZeroDrop(apiKey, baseUrl, client);
        }
    }
}
