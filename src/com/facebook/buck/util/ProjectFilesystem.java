/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Properties;

/**
 * An injectable service for interacting with the filesystem.
 */
public class ProjectFilesystem {

  private final File projectRoot;
  private final Function<String, String> pathRelativizer;
  private final ImmutableSet<String> ignorePaths;

  /**
   * There should only be one {@link ProjectFilesystem} created per process.
   * <p>
   * When creating a {@code ProjectFilesystem} for a test, rather than create a filesystem with an
   * arbitrary argument for the project root, such as {@code new File(".")}, prefer the creation of
   * a mock filesystem via EasyMock instead. Note that there are cases (such as integration tests)
   * where specifying {@code new File(".")} as the project root might be the appropriate thing.
   */
  public ProjectFilesystem(File projectRoot, ImmutableSet<String> ignorePaths) {
    this.projectRoot = Preconditions.checkNotNull(projectRoot);
    Preconditions.checkArgument(projectRoot.isDirectory());
    this.pathRelativizer = new Function<String, String>() {
      @Override
      public String apply(String relativePath) {
        return ProjectFilesystem.this.getFileForRelativePath(relativePath).getAbsolutePath();
      }
    };
    this.ignorePaths = Preconditions.checkNotNull(ignorePaths);
  }

  public ProjectFilesystem(File projectRoot) {
    this(projectRoot, ImmutableSet.<String>of());
  }

  public File getProjectRoot() {
    return projectRoot;
  }

  public ImmutableSet<String> getIgnorePaths() {
    return ignorePaths;
  }

  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot
        : new File(projectRoot, pathRelativeToProjectRoot);
  }

  public boolean exists(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).exists();
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   * @param pathRelativeToProjectRoot path to the file
   * @return true if the file was successfully deleted, false otherwise
   */
  public boolean deleteFileAtPath(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).delete();
  }

  public Properties readPropertiesFile(String pathToPropertiesFileRelativeToProjectRoot)
      throws IOException {
    Properties properties = new Properties();
    File propertiesFile = getFileForRelativePath(pathToPropertiesFileRelativeToProjectRoot);
    properties.load(Files.newReader(propertiesFile, Charsets.UTF_8));
    return properties;
  }

  /**
   * Checks whether there is a normal file at the specified path.
   */
  public boolean isFile(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).isFile();
  }

  /**
   * Allows {@link java.nio.file.Files#walkFileTree} to be faked in tests.
   */
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    java.nio.file.Files.walkFileTree(root, fileVisitor);
  }

  /**
   * Allows {@link java.nio.file.Files#isDirectory} to be faked in tests.
   */
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return java.nio.file.Files.isDirectory(child, linkOptions);
  }

  /**
   * Allows {@link java.io.File#listFiles} to be faked in tests.
   */
  public File[] listFiles(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).listFiles();
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(String path) throws IOException {
    MoreFiles.rmdir(pathRelativizer.apply(path));
  }

  /**
   * Resolves the relative path against the project root and then calls {@link File#mkdirs()}.
   */
  public boolean mkdirs(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).mkdirs();
  }

  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    Files.createParentDirs(file);
  }

  public void writeLinesToPath(Iterable<String> lines, String pathRelativeToProjectRoot)
      throws IOException {
    MoreFiles.writeLinesToFile(lines, getFileForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLine(String pathRelativeToProjectRoot) {
    Preconditions.checkNotNull(pathRelativeToProjectRoot);
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty,
   * or encounters an error while being read, {@link Optional#absent()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLineFromFile(File file) {
    try {
      String firstLine = Files.readFirstLine(file, Charsets.UTF_8);
      return Optional.fromNullable(firstLine);
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.absent();
    }
  }

  public Optional<File> getFileIfExists(String path) {
    File file = new File(path);
    if (file.exists()) {
      return Optional.of(file);
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return a function that takes a path relative to the project root and resolves it to an
   *     absolute path. This is particularly useful for {@link com.facebook.buck.step.Step}s that do
   *     not extend {@link com.facebook.buck.shell.ShellStep} because they are not guaranteed to be
   *     run from the project root.
   */
  public Function<String, String> getPathRelativizer() {
    return pathRelativizer;
  }

  /**
   * @param event The event to be tested.
   * @return true if event is a path change notification.
   */
  public boolean isPathChangeEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_MODIFY ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  public void copyFolder(String source, String target) throws IOException {
    Path targetPath = java.nio.file.Paths.get(pathRelativizer.apply(target));
    Path sourcePath = java.nio.file.Paths.get(pathRelativizer.apply(source));
    MoreFiles.copyRecursively(sourcePath, targetPath);
  }

  public void copyFile(String source, String target) throws IOException {
    Path targetPath = java.nio.file.Paths.get(pathRelativizer.apply(target));
    Path sourcePath = java.nio.file.Paths.get(pathRelativizer.apply(source));
    java.nio.file.Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
  }

  public void createSymLink(Path sourcePath, Path targetPath) throws IOException {
    if (Platform.detect() == Platform.WINDOWS) {
      if (isDirectory(sourcePath)) {
        // Creating symlinks to directories on Windows requires escalated privileges. We're just
        // going to have to copy things recursively.
        MoreFiles.copyRecursively(sourcePath, targetPath);
      } else {
        java.nio.file.Files.createLink(targetPath, sourcePath);
      }
    } else {
      java.nio.file.Files.createSymbolicLink(targetPath, sourcePath);
    }
  }
}
