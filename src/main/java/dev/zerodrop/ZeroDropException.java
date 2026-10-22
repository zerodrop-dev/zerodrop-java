package dev.zerodrop;

/** Base exception for all ZeroDrop errors. */
public class ZeroDropException extends RuntimeException {
    public ZeroDropException(String message) {
        super(message);
    }

    public ZeroDropException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Thrown when no email arrives within the timeout. */
    public static final class Timeout extends ZeroDropException {
        Timeout(String inbox, long timeoutSeconds) {
            super("No email received at \"" + inbox + "\" within " + timeoutSeconds +
                  "s — check that your app is sending to the correct address");
        }
    }

    /**
     * Thrown on a 401: the inbox belongs to an account's prefix and the request
     * had no key or the wrong one. The message carries the server's reason.
     */
    public static final class Auth extends ZeroDropException {
        Auth() {
            this(null);
        }

        Auth(String reason) {
            super(reason != null ? reason : "Invalid or missing API key");
        }
    }

    /** Thrown on transport-level failures. */
    public static final class Network extends ZeroDropException {
        Network(String message) {
            super(message + " (check https://zerodrop.instatus.com)");
        }

        Network(String message, Throwable cause) {
            super(message + " (check https://zerodrop.instatus.com)", cause);
        }
    }
}
