package com.sourcegraph.langp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

class DefaultConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConfiguration.class);

    static boolean prepare(Path path) {
        LOGGER.info("Scanning for Java sources in {}", path);
        // reading all the java directories in workspace
        Set<String> directories = getSourceDirs(path);
        // if we found no directories, let's try to add root one
        if (directories.isEmpty()) {
            directories.add(path.toAbsolutePath().normalize().toString());
        }
        LanguageServerConfiguration configuration = new LanguageServerConfiguration();
        configuration.classPath = new LinkedList<>();
        configuration.sources = directories;
        configuration.write(path, path.resolve(".jls-config"));

        return true;
    }

    /**
     * @param path workspace root
     * @return all "java" directories found
     */
    private static Set<String> getSourceDirs(Path path) {
        Set<String> dirs = new HashSet<>();
        try {

            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getFileName().toString().equals("java")) {
                        dirs.add(dir.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to collect java directories", e);
        }
        return dirs;
    }


}