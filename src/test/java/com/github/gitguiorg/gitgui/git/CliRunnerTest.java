package com.github.gitguiorg.gitgui.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliRunnerTest {
    @TempDir Path tmp;

    @Test
    void cancellationKillsTheProcess() throws Exception {
        Path marker = tmp.resolve("started");
        CliRunner runner = runner();
        CancelToken token = new CancelToken();

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() ->
                runner.run(token, "-cp", classpath(), Sleeper.class.getName(), marker.toString()));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(25);
            assertThat(Files.exists(marker)).as("child process started").isTrue();

            token.cancel();
            assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasMessageContaining("cancelled");
        } finally {
            token.cancel();
        }
    }

    @Test
    void keepsStderrOutOfSuccessfulStdout() {
        String out = runner().runStdout(null, "-cp", classpath(), Streams.class.getName());

        assertThat(out).isEqualTo("stdout\n");
    }

    private CliRunner runner() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new CliRunner(tmp, java, "java", Duration.ofSeconds(10), Map.of());
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    /** Child process: signals that it is running, then waits to be killed by the test. */
    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "started");
            Thread.sleep(30_000);
        }
    }

    /** Child process: writes to both streams to verify that success returns stdout only. */
    public static final class Streams {
        public static void main(String[] args) {
            System.out.print("stdout\n");
            System.err.print("stderr\n");
        }
    }
}
