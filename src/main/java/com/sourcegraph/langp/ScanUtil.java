package com.sourcegraph.langp;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;

/**
 * File scan utilities
 */
class ScanUtil {

    private ScanUtil() {
    }

    /**
     * Retrieves all matching files in the given root
     * @param root root directory
     * @param fileName file name to match against
     * @return found files
     * @throws IOException
     */
    static Collection<Path> findMatchingFiles(Path root, String fileName) throws IOException {
        Collection<Path> result = new HashSet<>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (fileName.equals(name)) {
                    result.add(file.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip common build data directories and dot-directories.
                String dirName = dir.getFileName().normalize().toString();
                if (dirName.equals("build") || dirName.equals("target") || dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }
}
