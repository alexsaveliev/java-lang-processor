package com.sourcegraph.langp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.Collectors;

class LanguageServerConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageServerConfiguration.class);

    Collection<String> sources;
    Collection<String> classPath;
    String outputDirectory;

    void write(Path workspaceRoot, Path target) {
        if (sources != null) {
            sources = sources.
                    stream().
                    map(s -> workspaceRoot.resolve(s).toAbsolutePath().normalize().toString()).
                    collect(Collectors.toList());
        }
        if (classPath != null) {
            classPath = classPath.
                    stream().
                    map(s -> workspaceRoot.resolve(s).toAbsolutePath().normalize().toString()).
                    collect(Collectors.toList());
        }
        if (outputDirectory != null) {
            outputDirectory = workspaceRoot.resolve(outputDirectory).toString();
        }
        try (FileWriter writer = new FileWriter(target.toFile())) {
            JSONUtil.write(this, writer);
            LOGGER.info("Wrote {}", target);
        } catch (IOException e) {
            LOGGER.warn("Failed to save configuration", e);
        }

    }
}
