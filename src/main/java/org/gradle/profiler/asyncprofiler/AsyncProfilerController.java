package org.gradle.profiler.asyncprofiler;

import com.google.common.collect.ImmutableList;
import org.gradle.profiler.CommandExec;
import org.gradle.profiler.GradleScenarioDefinition;
import org.gradle.profiler.Invoker;
import org.gradle.profiler.ScenarioSettings;
import org.gradle.profiler.SingleRecordingProfilerController;
import org.gradle.profiler.flamegraph.FlameGraphSanitizer;
import org.gradle.profiler.flamegraph.FlameGraphTool;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.gradle.profiler.asyncprofiler.AsyncProfilerConfig.Counter;
import static org.gradle.profiler.flamegraph.FlameGraphSanitizer.*;

public class AsyncProfilerController extends SingleRecordingProfilerController {
    private final AsyncProfilerConfig profilerConfig;
    private final String pid;
    private final ScenarioSettings scenarioSettings;
    private final FlameGraphTool flamegraphGenerator;
    private final FlameGraphSanitizer flamegraphSanitizer;

    public AsyncProfilerController(AsyncProfilerConfig profilerConfig, String pid, ScenarioSettings scenarioSettings) {
        this.profilerConfig = profilerConfig;
        this.pid = pid;
        this.scenarioSettings = scenarioSettings;
        ImmutableList.Builder<SanitizeFunction> sanitizers = ImmutableList.<SanitizeFunction>builder();
        if (!profilerConfig.isIncludeSystemThreads()) {
            sanitizers.add(new RemoveSystemThreads());

        }
        sanitizers.add(COLLAPSE_BUILD_SCRIPTS, COLLAPSE_GRADLE_INFRASTRUCTURE, SIMPLE_NAMES);
        this.flamegraphSanitizer = new FlameGraphSanitizer(sanitizers.build().toArray(new SanitizeFunction[0]));
        this.flamegraphGenerator = new FlameGraphTool();
    }

    @Override
    public String getName() {
        return "async profiler";
    }

    @Override
    protected void doStartRecording() {
        if (scenarioSettings.getScenario().getInvoker() != Invoker.NoDaemon) {
            new CommandExec().run(
                getProfilerScript().getAbsolutePath(),
                "start",
                "-e", profilerConfig.getEvent(),
                "-i", String.valueOf(profilerConfig.getInterval()),
                "-j", String.valueOf(profilerConfig.getStackDepth()),
                "-b", String.valueOf(profilerConfig.getFrameBuffer()),
                pid
            );
        }
    }

    @Override
    public void stopSession() {
        GradleScenarioDefinition scenario = scenarioSettings.getScenario();
        File stacks = AsyncProfiler.stacksFileFor(scenario);
        if (scenarioSettings.getScenario().getInvoker() != Invoker.NoDaemon) {
            new CommandExec().run(
                getProfilerScript().getAbsolutePath(),
                "stop",
                "-o", "collapsed=" + profilerConfig.getCounter().name().toLowerCase(Locale.ROOT),
                "-a",
                "-f", stacks.getAbsolutePath(),
                pid
            );
        }
        if (flamegraphGenerator.checkInstallation()) {
            File simplifiedStacks = new File(scenario.getOutputDir(), scenario.getProfileName() + ".simplified-stacks.txt");
            flamegraphSanitizer.sanitize(stacks, simplifiedStacks);

            Counter counter = profilerConfig.getCounter();
            String unit = counter == Counter.SAMPLES ? "samples" : "units";
            String titlePrefix = profilerConfig.getEvent().toUpperCase(Locale.ROOT);
            File flamegraph = new File(scenario.getOutputDir(), scenario.getProfileName() + "-flames.svg");
            flamegraphGenerator.generateFlameGraph(
                simplifiedStacks, flamegraph,
                "--colors", "java",
                "--minwidth", "1",
                "--title", titlePrefix + " Flame Graph",
                "--countname", unit
            );
            File iciclegraph = new File(scenario.getOutputDir(), scenario.getProfileName() + "-icicles.svg");
            flamegraphGenerator.generateFlameGraph(
                simplifiedStacks, iciclegraph,
                "--reverse", "--invert",
                "--colors", "java",
                "--minwidth", "2",
                "--title", titlePrefix + " Icicle Graph",
                "--countname", unit
            );
        }
    }

    private File getProfilerScript() {
        return new File(profilerConfig.getProfilerHome(), "profiler.sh");
    }

    private static final class RemoveSystemThreads implements SanitizeFunction {

        @Override
        public List<String> map(List<String> stack) {
            for (String frame : stack) {
                if (frame.contains("GCTaskThread") || frame.contains("JavaThread")) {
                    return Collections.emptyList();
                }
            }
            return stack;
        }
    }

}
