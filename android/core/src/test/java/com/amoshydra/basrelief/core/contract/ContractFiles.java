package com.amoshydra.basrelief.core.contract;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Reading the repository-root {@code contract/} fixtures, for the tests that hold
 * each implementation to the same bytes.
 *
 * <p><b>The path comes from the Gradle test task, not from a relative chain.</b>
 * The test working directory is {@code android/core}, the fixtures are two levels
 * up at the repository root, and the build container mounts only {@code android/}
 * at {@code /workspace} — so the same relative path is not the same place in the
 * two environments. {@code android/core/build.gradle} passes the directory as the
 * {@code basrelief.contract} system property and
 * {@code android/podman-build.sh} mounts it at {@code /contract}.
 */
final class ContractFiles {

    private ContractFiles() {
    }

    /** The fixtures' directory; fails loudly rather than guessing a relative path. */
    static Path root() {
        String dir = System.getProperty("basrelief.contract");
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException(
                    "basrelief.contract is not set — these tests read contract/ at the "
                            + "repository root, and the path is passed from "
                            + "android/core/build.gradle. Run them through Gradle.");
        }
        Path path = Paths.get(dir);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    "basrelief.contract points at " + dir + ", which is not a directory");
        }
        return path;
    }

    static byte[] bytes(String... parts) {
        Path path = root();
        for (String part : parts) path = path.resolve(part);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + path, e);
        }
    }

    static JSONObject json(String... parts) {
        return new JSONObject(new String(bytes(parts), StandardCharsets.UTF_8));
    }

    /** Lowercase hex SHA-256, the digest form the fixtures state. */
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
