# health-tray

[![Maven Central](https://img.shields.io/maven-central/v/com.fathzer/health-tray)](https://central.sonatype.com/artifact/com.fathzer/health-tray)
[![License](https://img.shields.io/github/license/fathzer/health-tray)](LICENSE)
[![SonarCloud](https://sonarcloud.io/api/project_badges/measure?project=fathzer_health-tray&metric=alert_status)](https://sonarcloud.io/dashboard?id=fathzer_health-tray)
[![javadoc](https://javadoc.io/badge2/com.fathzer/health-tray/javadoc.svg)](https://javadoc.io/doc/com.fathzer/health-tray)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/fathzer/health-tray)

A reusable Java library for easily building system-tray health monitoring applications with live status.

## Purpose

**health-tray** lets you build a desktop system-tray application that periodically runs health checks
and alerts the user when something goes wrong. It handles all the boilerplate for you:

- A **system-tray icon** that turns green when all checks pass, red when at least one fails.
- **Desktop notifications** via [Dorkbox Notify](https://gitlab.com/dorkbox/Notify):
  - Persistent error notifications that stay visible until the check recovers or the user closes them.
  - A short recovery notification ("&lt;name&gt; is ok") when a failed check returns to OK.
- A **status window** showing a live table of all checks (name, state, message, period, last check,
  last change time), refreshed in real time.
- **State persistence**: task status, message, and timestamps are saved on shutdown and restored on
  the next startup, so transient errors are not lost across restarts.
- **Duplicate name detection**: if two tasks share the same name, the application refuses to start
  and shows a configuration error notification with a gray tray icon.

You only need to provide the list of `CheckTask` instances and call `HealthTray.launch(...)`.

## Requirements

- **Java 17** or later.
- A desktop environment with system-tray support (Windows, macOS, or Linux with a system tray).
- Maven (or Gradle) to add the dependency.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>com.fathzer</groupId>
  <artifactId>health-tray</artifactId>
  <version>0.0.1</version>
</dependency>
```

## Quick start

Create a list of `CheckTask` instances and launch the application:

```java
import com.fathzer.healthtray.HealthTray;
import com.fathzer.healthtray.HttpCheckTask;
import java.util.List;

public class MyApp {
    public static void main(String[] args) {
        HealthTray.launch(List.of(
            HttpCheckTask.builder("My site", "https://example.com/health", 60).build(),
            HttpCheckTask.builder("API", "https://api.example.com/ping", 30).build()
        ));
    }
}
```

That's it. The tray icon appears, checks run immediately and then at their configured period,
and you get notifications on failures and recoveries.

## Built-in checks

### HttpCheckTask

Verifies that an HTTP(S) URL returns an accepted status code. Created via a builder:

```java
import java.util.Set;
import com.fathzer.healthtray.HttpCheckTask;
import com.fathzer.healthtray.CheckTask.Status;
import com.fathzer.healthtray.CheckTask.TaskResult;

HttpCheckTask.builder("My API", "https://api.example.com/health", 60)
    .okCodes(Set.of(200, 204))           // default: Set.of(200)
    .connectTimeout(Duration.ofSeconds(5)) // default: 10s
    .requestTimeout(Duration.ofSeconds(5)) // default: 10s, null for none
    .verify(response -> {                  // default: OK with "HTTP <code>"
        // Additional verification on the response (headers, etc.)
        return new TaskResult(Status.OK, "All good");
    })
    .build();
```

For advanced HTTP configuration (proxy, authenticator, SSL context, etc.), use
`httpClientBuilder()` to access the underlying `HttpClient.Builder`:

```java
HttpCheckTask.builder("Internal API", "https://internal.example.com/health", 60)
    .httpClientBuilder()
        .proxy(ProxySelector.of(new InetSocketAddress("proxy.example.com", 8080)))
    .build();
```

## Creating custom checks

A custom check is a subclass of `CheckTask` that implements `doRun()`.
Each check returns a `TaskResult` containing a `Status` (`OK` or `ERROR`) and a message.

### Example: monitoring a file's content

The following check reports `OK` when a file is empty or missing, and `ERROR` with the file's
content as the message when the file contains text. This is useful for monitoring a status file
that an external process writes to when something goes wrong.

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fathzer.healthtray.CheckTask;
import com.fathzer.healthtray.CheckTask.Status;
import com.fathzer.healthtray.CheckTask.TaskResult;

public class StatusFileCheck extends CheckTask {
    private final Path file;

    public StatusFileCheck(String name, Path file, long periodSeconds) {
        super(name, periodSeconds);
        this.file = file;
    }

    @Override
    protected TaskResult doRun() {
        try {
            if (!Files.exists(file) || Files.size(file) == 0L) {
                return new TaskResult(Status.OK, "file is empty");
            }
            String content = Files.readString(file).strip();
            return content.isEmpty()
                    ? new TaskResult(Status.OK, "file is empty")
                    : new TaskResult(Status.ERROR, content);
        } catch (IOException e) {
            return new TaskResult(Status.ERROR, "Unable to read " + file + ": " + e.getMessage());
        }
    }
}
```

### How it works

- **`doRun()`** is called immediately at startup (via `init()`) and then every `periodSeconds`.
- **`doInit()`** defaults to calling `doRun()`. Override it if the initial check should behave
  differently from periodic checks.
- The base class tracks `status`, `message`, `lastCheck`, and `lastChange` automatically.
- `lastChange` is only updated on an actual status transition (not on the first observation).
- Listeners can subscribe to `onCheckDone` (fired after every check) and `onStateChange`
  (fired only when the status changes). The UI and notification system use these internally.

## State persistence

Task state is saved to a properties file on shutdown and restored on the next startup.

- **Default file**: `health-state.properties` in the working directory.
- **Custom file**: pass a `Path` to `HealthTray.launch(tasks, stateFile)`.

The merge logic on startup is:
- If `init()` succeeds (OK): all saved fields (status, message, timestamps) are restored.
- If `init()` fails (ERROR): the error and message from `init()` are kept, `lastCheck` is taken
  from the saved state, and `lastChange` is taken from the saved state only if the saved status
  was already `ERROR` (otherwise it's a new error, so `lastChange` is null).

## License

See [LICENSE](LICENSE).
