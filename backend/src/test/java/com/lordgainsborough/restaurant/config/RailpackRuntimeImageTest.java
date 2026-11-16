package com.lordgainsborough.restaurant.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "railpack.runtime.image", matches = "true")
class RailpackRuntimeImageTest {
    private static final String IMAGE = "lgb-railpack-runtime-test:local";
    private static final String RUNTIME_IMAGE = "ghcr.io/railwayapp/railpack-runtime:debian-trixie";
    private static final Pattern START_COMMAND = Pattern.compile("(?m)^startCommand\\s*=\\s*\"([^\"]+)\"\\s*$");

    @Test
    void startupWrapperRunsInPublishedRailpackRuntimeImage() throws Exception {
        DockerCli docker = DockerCli.detect();
        assertThat(docker).as("Docker is required to run the published Railpack runtime image").isNotNull();

        Path jar = Path.of("target", "restaurant-api-0.0.1-SNAPSHOT.jar");
        assertThat(Files.size(jar)).as("package the backend before this test").isGreaterThan(1_000_000);
        String command = startCommand();
        assertThat(command).isEqualTo(
                "sh target/railway-start.sh java ${JAVA_OPTS:-} -jar target/restaurant-api-0.0.1-SNAPSHOT.jar");

        CommandResult baseTools = docker.run(10, RUNTIME_IMAGE,
                "command -v sh || echo MISSING_SH; command -v base64 || echo MISSING_BASE64; "
                        + "command -v openssl || echo MISSING_OPENSSL; openssl version || true");
        assertThat(baseTools.exitCode).as(safe(baseTools.output)).isZero();
        assertThat(baseTools.output)
                .doesNotContain("MISSING_SH")
                .doesNotContain("MISSING_BASE64")
                .doesNotContain("MISSING_OPENSSL");

        Path context = Path.of("target", "railpack-image-context");
        if (Files.exists(context)) {
            try (var paths = Files.walk(context)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
        }
        Files.createDirectories(context);
        Files.copy(Path.of("target", "railway-start.sh"), context.resolve("railway-start.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(jar, context.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src", "test", "railpack", "verify-runtime.sh"), context.resolve("verify-runtime.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src", "test", "docker", "RailpackRuntime.Dockerfile"), context.resolve("Dockerfile"),
                StandardCopyOption.REPLACE_EXISTING);

        CommandResult build = docker.execute(15, "build", "-f", docker.path(context.resolve("Dockerfile")),
                "-t", IMAGE, docker.path(context));
        assertThat(build.exitCode).as(safe(build.output)).isZero();

        CommandResult run = docker.execute(4, "run", "--rm", "--network", "none",
                "-e", "START_COMMAND=" + command, IMAGE);
        assertThat(run.exitCode).as(safe(run.output)).isZero();
        assertThat(run.output).contains("RUNTIME_IMAGE_OK")
                .contains("MODE:verify-full")
                .contains("ROOT:/tmp/postgres-ca.pem")
                .contains("ARG:-Xmx256m")
                .contains("ARG:-jar")
                .contains("ARG:target/restaurant-api-0.0.1-SNAPSHOT.jar")
                .contains("SH:")
                .contains("BASE64:")
                .contains("OPENSSL:")
                .doesNotContain("BEGIN CERTIFICATE")
                .doesNotContain("PRIVATE KEY")
                .doesNotContain("PRIVATE_KEY_PRESENT");
        docker.execute(2, "image", "rm", IMAGE);
    }

    private static String startCommand() throws Exception {
        Matcher matcher = START_COMMAND.matcher(Files.readString(Path.of("railway.toml")));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String safe(String value) {
        String redacted = value
                .replaceAll("-----BEGIN [A-Z ]+-----", "[redacted]")
                .replaceAll("-----END [A-Z ]+-----", "[redacted]")
                .replaceAll("(?m)^[A-Za-z0-9+/=]{40,}$", "[redacted]");
        if (redacted.length() > 4000) {
            return redacted.substring(redacted.length() - 4000);
        }
        return redacted;
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record DockerCli(List<String> prefix, boolean wsl) {
        static DockerCli detect() throws Exception {
            List<Path> candidates = new ArrayList<>();
            candidates.add(Path.of("C:/Program Files/Docker/Docker/resources/bin/docker.exe"));
            String path = System.getenv("PATH");
            if (path != null) {
                for (String entry : path.split(Pattern.quote(java.io.File.pathSeparator))) {
                    if (!entry.isBlank()) {
                        candidates.add(Path.of(entry, "docker.exe"));
                        candidates.add(Path.of(entry, "docker"));
                    }
                }
            }
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return new DockerCli(List.of(candidate.toString()), false);
                }
            }
            CommandResult wsl = runProcess(1, List.of("wsl.exe", "-d", "Debian", "--", "docker", "info"));
            if (wsl.exitCode == 0) {
                return new DockerCli(List.of("wsl.exe", "-d", "Debian", "--", "docker"), true);
            }
            return null;
        }

        CommandResult run(int timeoutMinutes, String image, String shellCommand) throws Exception {
            return execute(timeoutMinutes, "run", "--rm", "--network", "none", "--entrypoint", "sh", image, "-c", shellCommand);
        }

        CommandResult execute(int timeoutMinutes, String... arguments) throws Exception {
            List<String> command = new ArrayList<>(prefix);
            for (String argument : arguments) {
                command.add(argument);
            }
            return runProcess(timeoutMinutes, command);
        }

        String path(Path path) {
            Path absolute = path.toAbsolutePath();
            if (!wsl) {
                return absolute.toString();
            }
            String value = absolute.toString().replace('\\', '/');
            return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }

        private static CommandResult runProcess(int timeoutMinutes, List<String> command) throws Exception {
            Process process = new ProcessBuilder(command).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread stdout = Thread.startVirtualThread(() -> transfer(process.getInputStream(), output));
            Thread stderr = Thread.startVirtualThread(() -> transfer(process.getErrorStream(), output));
            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "timed out\n" + output.toString(StandardCharsets.UTF_8));
            }
            stdout.join(5000);
            stderr.join(5000);
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        }

        private static void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
            try (input) {
                input.transferTo(output);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
