# zerodrop-java

[![JitPack](https://jitpack.io/v/zerodrop-dev/zerodrop-java.svg)](https://jitpack.io/#zerodrop-dev/zerodrop-java)
[![CI](https://github.com/zerodrop-dev/zerodrop-java/actions/workflows/ci.yml/badge.svg)](https://github.com/zerodrop-dev/zerodrop-java/actions/workflows/ci.yml)
[![license](https://img.shields.io/github/license/zerodrop-dev/zerodrop-java.svg)](LICENSE)

Email verification infrastructure for CI pipelines.

Send a verification email. Catch it at the edge. Get `email.otp()` and `email.magicLink()` back — auto-extracted, no regex, no Docker, no signup to try.

```java
Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));

email.otp();       // Optional["123456"] — auto-extracted
email.magicLink(); // Optional["https://..."] — no regex needed
```

**[Documentation](https://docs.zerodrop.dev)** · [GitHub](https://github.com/zerodrop-dev) · [Status](https://zerodrop.instatus.com)

## Install

**Gradle:**

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    testImplementation 'com.github.zerodrop-dev:zerodrop-java:v0.3.0'
}
```

**Maven:**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.zerodrop-dev</groupId>
    <artifactId>zerodrop-java</artifactId>
    <version>v0.3.0</version>
    <scope>test</scope>
</dependency>
```

Java 11+ · one dependency (Gson).

## Quick start

```java
import dev.zerodrop.ZeroDrop;
import dev.zerodrop.Email;
import java.time.Duration;

ZeroDrop mail = ZeroDrop.create();
String inbox = mail.generateInbox();
// => "swift-x7k29ab@zerodrop-sandbox.online"

// Trigger your app's email flow with the inbox address...

Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));

email.subject();   // "Verify your email"
email.otp();       // Optional["123456"] — auto-extracted, no regex
email.magicLink(); // Optional["https://..."] — auto-extracted
```

## JUnit 5 + Selenium

```java
import dev.zerodrop.ZeroDrop;
import dev.zerodrop.Email;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SignupTest {
    private final ZeroDrop mail = ZeroDrop.create();

    @Test
    void emailVerificationWithOtp() {
        String inbox = mail.generateInbox();

        driver.get("https://yourapp.com/signup");
        driver.findElement(By.name("email")).sendKeys(inbox);
        driver.findElement(By.name("password")).sendKeys("TestPassword123!");
        driver.findElement(By.cssSelector("button[type=submit]")).click();

        Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));

        String otp = email.otp().orElseThrow(
            () -> new AssertionError("expected OTP in verification email"));

        driver.findElement(By.name("otp")).sendKeys(otp);
        driver.findElement(By.cssSelector("button[type=submit]")).click();

        assertTrue(driver.getCurrentUrl().endsWith("/dashboard"));
    }
}
```

## Playwright for Java

```java
import com.microsoft.playwright.*;
import dev.zerodrop.ZeroDrop;
import dev.zerodrop.Email;
import java.time.Duration;

try (Playwright playwright = Playwright.create()) {
    Browser browser = playwright.chromium().launch();
    Page page = browser.newPage();

    ZeroDrop mail = ZeroDrop.create();
    String inbox = mail.generateInbox();

    page.navigate("https://yourapp.com/signup");
    page.fill("[name=email]", inbox);
    page.fill("[name=password]", "TestPassword123!");
    page.click("button[type=submit]");

    Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));

    page.fill("[name=otp]", email.otp().orElseThrow());
    page.click("button[type=submit]");

    assertThat(page).hasURL("https://yourapp.com/dashboard");
}
```

## Email filtering

```java
import dev.zerodrop.Filter;

Filter filter = Filter.builder()
    .from("noreply@yourapp.com")
    .subject("Verify")
    .hasOtp(true)
    .build();

Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15), filter);
```

All string filters are case-insensitive partial matches.

## Magic link flows

```java
Filter filter = Filter.builder().hasMagicLink(true).build();
Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15), filter);

driver.get(email.magicLink().orElseThrow());
assertTrue(driver.getCurrentUrl().endsWith("/dashboard"));
```

## Free API key (private inboxes)

Without a key, anyone who knows an inbox's name can read it, and one sender can reach only 5 different inboxes a day. With a free key, your inboxes start with your own prefix, only your key can read them, and mail to them isn't limited.

1. Sign in with GitHub at [zerodrop.dev/keys](https://zerodrop.dev/keys) and create a key.
2. Set it as `ZERODROP_API_KEY`. `ZeroDrop.create()` reads it automatically, or you can pass `.apiKey(...)` to the builder.

```java
ZeroDrop mail = ZeroDrop.create(); // reads ZERODROP_API_KEY when it's set
mail.generateInbox();
// "k7x2-swift-x7k29ab@zerodrop-sandbox.online", where k7x2 is your prefix
```

Reading a `k7x2-…` inbox without your key throws `ZeroDropException.Auth` with the reason. `mail.prefix()` returns your prefix if you build an address yourself. A key holding a character that can't be sent in an HTTP header (a line break, a byte-order mark) makes `ZeroDrop.create()` and `build()` throw `IllegalArgumentException`; the message never includes the key.

Without a key, if ZeroDrop held an email because its sender passed the daily limit, the inbox gets a notice instead, and `fetchLatest` and `waitForLatest` throw `ZeroDropException.Held` saying so (v0.3.0 or later; older versions return the notice as if it were the email, or time out when a filter skips it). A free key from [zerodrop.dev/keys](https://zerodrop.dev/keys) avoids the limit.

## Parallel test runs

`generateInbox()` runs locally — no network request, no collisions. Safe with JUnit parallel execution and TestNG parallel suites; every call returns a unique isolated inbox. Mail to many inboxes a day needs a free API key: without one, one sender can reach only 5 different inboxes a day.

## Error handling

```java
import dev.zerodrop.ZeroDropException;

try {
    Email email = mail.waitForLatest(inbox, Duration.ofSeconds(15));
} catch (ZeroDropException.Timeout e) {
    // No email arrived — check your app is sending correctly
} catch (ZeroDropException.Auth e) {
    // The inbox belongs to another key's prefix, or your key is wrong — e.getMessage() says which
} catch (ZeroDropException.Held e) {
    // No key, and ZeroDrop held the email (daily limit) — e.getMessage() has the reason
} catch (ZeroDropException.Network e) {
    // Transport failure — message includes status page link
}
```

## Workspaces

```java
ZeroDrop mail = ZeroDrop.builder()
    .apiKey(System.getenv("ZERODROP_API_KEY"))
    .build();
```

## Self-hosted

```java
ZeroDrop mail = ZeroDrop.builder()
    .baseUrl("https://your-instance.yourdomain.com")
    .build();
```

## API

| Method | Description |
|---|---|
| `ZeroDrop.create()` | Client using `ZERODROP_API_KEY` when set; otherwise free sandbox mode, where inboxes are public to anyone who knows the name. |
| `ZeroDrop.builder()` | Configure apiKey / baseUrl / httpClient. |
| `generateInbox()` | Instant inbox address. No network request. With a key, the name starts with your prefix. |
| `prefix()` | `Optional<String>` — your key's inbox prefix (`"k7x2"`), empty without a key. |
| `fetchLatest(inbox, filter)` | `Optional<Email>` — latest matching email. |
| `waitForLatest(inbox, timeout, filter)` | Block until email arrives. |

## Free vs Workspace

|  | Free | Workspace |
|---|---|---|
| Inbox generation | ✓ | ✓ |
| OTP auto-extraction | ✓ | ✓ |
| Magic link extraction | ✓ | ✓ |
| Email filtering | ✓ | ✓ |
| Email retention | 30 min | Extended |
| Custom domains | ✗ | ✓ |
| API key | ✓ free at [zerodrop.dev/keys](https://zerodrop.dev/keys) | ✓ |
| Daily limit per sender | 5 inboxes without a key, none with one | None |

Get a Workspace at [zerodrop.dev](https://zerodrop.dev)

## License

MIT
