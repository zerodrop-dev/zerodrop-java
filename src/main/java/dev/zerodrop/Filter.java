package dev.zerodrop;

import java.util.Locale;

/**
 * Filter for narrowing which email is returned.
 * All string matches are case-insensitive partial matches.
 *
 * <pre>{@code
 * Filter filter = Filter.builder()
 *     .from("noreply@yourapp.com")
 *     .hasOtp(true)
 *     .build();
 * }</pre>
 */
public final class Filter {
    private final String from;
    private final String subject;
    private final String body;
    private final Boolean hasOtp;
    private final Boolean hasMagicLink;

    private Filter(Builder b) {
        this.from = b.from;
        this.subject = b.subject;
        this.body = b.body;
        this.hasOtp = b.hasOtp;
        this.hasMagicLink = b.hasMagicLink;
    }

    public static Builder builder() {
        return new Builder();
    }

    boolean matches(Email email) {
        if (from != null && !containsIgnoreCase(email.from(), from)) return false;
        if (subject != null && !containsIgnoreCase(email.subject(), subject)) return false;
        if (body != null && !containsIgnoreCase(email.body(), body)) return false;
        if (hasOtp != null) {
            boolean present = email.otp().isPresent();
            if (hasOtp != present) return false;
        }
        if (hasMagicLink != null) {
            boolean present = email.magicLink().isPresent();
            if (hasMagicLink != present) return false;
        }
        return true;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public static final class Builder {
        private String from;
        private String subject;
        private String body;
        private Boolean hasOtp;
        private Boolean hasMagicLink;

        public Builder from(String from) { this.from = from; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder hasOtp(boolean hasOtp) { this.hasOtp = hasOtp; return this; }
        public Builder hasMagicLink(boolean hasMagicLink) { this.hasMagicLink = hasMagicLink; return this; }

        public Filter build() { return new Filter(this); }
    }
}
