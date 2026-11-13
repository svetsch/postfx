package io.postfx.io;

import io.postfx.engine.JsonUtil;
import io.postfx.model.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads / saves the workspace as JSON in ~/.postfx/workspace.json (atomic write, one backup). */
public class WorkspaceStore {
    private final Path file;

    public WorkspaceStore(Path file) {
        this.file = file;
    }

    public static WorkspaceStore defaultStore() {
        String override = System.getProperty("postfx.home");
        Path dir = override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".postfx");
        return new WorkspaceStore(dir.resolve("workspace.json"));
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public Workspace load() throws IOException {
        if (!Files.exists(file)) return SampleWorkspace.create();
        return JsonUtil.MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Workspace.class);
    }

    public synchronized void save(Workspace ws) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JsonUtil.toPrettyJson(ws), StandardCharsets.UTF_8);
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
