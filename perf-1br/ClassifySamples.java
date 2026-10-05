import java.nio.file.Path;
import java.util.*;
import jdk.jfr.consumer.*;

/**
 * One line per guest thread of a JFR recording, one column per second of
 * CPU-time samples: C when partial-evaluated code ran, I when THC's bytecode
 * interpreter ran host-compiled, J when it ran in the JVM's own interpreter,
 * h for other host code, . for no samples.
 *
 * Partial evaluation inlines the interpreter's continueAt into one nmethod of
 * OptimizedCallTarget.profiledPERoot; run as an interpreter, continueAt is a
 * frame of its own, being too large to inline. Sulong's interpreter has no
 * continueAt and shows as C, so check the top frames where C appears early.
 *
 * usage: java perf-1br/ClassifySamples.java run.jfr
 */
public class ClassifySamples {
    public static void main(String[] args) throws Exception {
        var events = RecordingFile.readAllEvents(Path.of(args[0]));
        long start = Long.MAX_VALUE;
        for (var event : events) start = Math.min(start, event.getStartTime().toEpochMilli());
        var timelines = new TreeMap<String, TreeMap<Integer, Map<Character, Integer>>>();
        for (var event : events) {
            if (!event.getEventType().getName().equals("jdk.CPUTimeSample")) continue;
            RecordedThread thread = event.getValue("eventThread");
            String name = thread == null ? null : thread.getJavaName();
            if (name == null || !(name.startsWith("Polyglot-thc") || name.equals("main"))) continue;
            int second = (int) ((event.getStartTime().toEpochMilli() - start) / 1000);
            timelines.computeIfAbsent(name, n -> new TreeMap<>()).computeIfAbsent(second, s -> new HashMap<>())
                .merge(kind(event.getStackTrace()), 1, Integer::sum);
        }
        System.out.println("origin " + java.time.Instant.ofEpochMilli(start));
        for (var timeline : timelines.entrySet()) {
            var line = new StringBuilder();
            for (int second = 0; second <= timeline.getValue().lastKey(); second++) {
                var counts = timeline.getValue().get(second);
                line.append(counts == null ? '.' : Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey());
            }
            System.out.printf("%-18s %s%n", timeline.getKey(), line);
        }
    }

    private static char kind(RecordedStackTrace stack) {
        if (stack == null) return 'h';
        for (var frame : stack.getFrames()) {
            if (frame.getType().equals("Inlined")) continue;
            String method = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
            if (method.matches(".*BytecodeNode\\.continueAt(_\\d+)?")) return frame.getType().equals("Interpreted") ? 'J' : 'I';
            if (method.endsWith("OptimizedCallTarget.profiledPERoot")) return 'C';
        }
        return 'h';
    }
}
