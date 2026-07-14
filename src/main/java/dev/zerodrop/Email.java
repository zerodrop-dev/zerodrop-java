package dev.zerodrop;

import java.time.Instant;
import java.util.Optional;

/**
 * A caught email with auto-extracted fields.
 */
public final class Email {
    private final String id;
    private final String from;
    private final String to;
    private final String subject;
    private final String body;
    private final String rawBody;
    private final Instant receivedAt;
    private final String otp;
    private final String magicLink;

    Email(String id, String from, String to, String subject, String body,
          String rawBody, Instant receivedAt, String otp, String magicLink) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.rawBody = rawBody;
        this.receivedAt = receivedAt;
        this.otp = otp;
        this.magicLink = magicLink;
    }

    public String id() { return id; }
    public String from() { return from; }
    public String to() { return to; }
    public String subject() { return subject; }
    public String body() { return body; }
    public String rawBody() { return rawBody; }
    public Optional<Instant> receivedAt() { return Optional.ofNullable(receivedAt); }

    /** Auto-extracted 4-8 digit verification code, if detected. */
    public Optional<String> otp() { return Optional.ofNullable(otp); }

    /** Auto-extracted verification/reset URL, if detected. */
    public Optional<String> magicLink() { return Optional.ofNullable(magicLink); }

    @Override
    public String toString() {
        return "Email{from=" + from + ", subject=" + subject +
               ", otp=" + (otp != null ? otp : "none") +
               ", magicLink=" + (magicLink != null ? "present" : "none") + "}";
    }
}
