package com.fathzer.healthtray;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Utility to temporarily silence a logger during tests.
 * <BR>Usage:
 * <pre>{@code
 * try (LogSilencer s = LogSilencer.silence(AbstractCheckTask.class)) {
 *     task.init(saved); // logs are silenced
 * }
 * }</pre>
 */
public final class LogSilencer implements AutoCloseable {
    private final Logger logger;
    private final Level oldLevel;

    private LogSilencer(Logger logger) {
        this.logger = logger;
        this.oldLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
    }

    /** Silences the logger of the given class for the duration of the returned instance.
     * @param clazz the class whose logger should be silenced.
     * @return a {@link LogSilencer} to close in a try-with-resources block. */
    public static LogSilencer silence(Class<?> clazz) {
        return silence(Logger.getLogger(clazz.getName()));
    }

    /** Silences the given logger for the duration of the returned instance.
     * @param logger the logger to silence.
     * @return a {@link LogSilencer} to close in a try-with-resources block. */
    public static LogSilencer silence(Logger logger) {
        return new LogSilencer(logger);
    }

    @Override
    public void close() {
        logger.setLevel(oldLevel);
    }
}
