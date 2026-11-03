package dev.twitchchaos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Minimaler Tick-Planer (laeuft ausschliesslich auf dem Server-Thread). */
public final class Scheduler {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Scheduler");

    private record Task(long runAt, Runnable action) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now;

    public long now() {
        return now;
    }

    public void schedule(int delayTicks, Runnable action) {
        tasks.add(new Task(now + Math.max(0, delayTicks), action));
    }

    public void clear() {
        tasks.clear();
    }

    public void tick() {
        now++;
        if (tasks.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        tasks.removeIf(t -> {
            if (t.runAt() <= now) {
                due.add(t);
                return true;
            }
            return false;
        });
        for (Task t : due) {
            try {
                t.action().run();
            } catch (Exception e) {
                LOG.warn("Geplante Aufgabe fehlgeschlagen: {}", e.toString());
            }
        }
    }
}
