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

package com.facebook.buck.parser;

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultBuildRuleBuilderParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A set of parameters passed to a {@link BuildRuleFactory}.
 */
public final class BuildRuleFactoryParams {
  static final String GENFILE_PREFIX = "BUCKGEN:";

  private final Map<String, ?> instance;
  private final ProjectFilesystem filesystem;
  private final BuildFileTree buildFiles;
  public final BuildTargetParser buildTargetParser;
  public final BuildTargetPatternParser buildTargetPatternParser;
  public final BuildTarget target;
  private final ParseContext buildFileParseContext;
  private final ParseContext visibilityParseContext;
  private final boolean ignoreFileExistenceChecks;
  private final AbstractBuildRuleBuilderParams abstractBuildRuleFactoryParams;

  private final Function<String, String> resolveFilePathRelativeToBuildFileDirectoryTransform;

  public BuildRuleFactoryParams(
      Map<String, ?> instance,
      ProjectFilesystem filesystem,
      BuildFileTree buildFiles,
      BuildTargetParser buildTargetParser,
      BuildTarget target) {
    this(instance,
        filesystem,
        buildFiles,
        buildTargetParser,
        target,
        false /* ignoreFileExistenceChecks */);
  }

  @VisibleForTesting
  BuildRuleFactoryParams(
      Map<String, ?> instance,
      ProjectFilesystem filesystem,
      BuildFileTree buildFiles,
      BuildTargetParser buildTargetParser,
      BuildTarget target,
      boolean ignoreFileExistenceChecks) {
    this.instance = instance;
    this.filesystem = filesystem;
    this.buildFiles = Preconditions.checkNotNull(buildFiles);
    this.buildTargetParser = buildTargetParser;
    this.buildTargetPatternParser = new BuildTargetPatternParser(filesystem);
    this.target = Preconditions.checkNotNull(target);
    this.visibilityParseContext = ParseContext.forVisibilityArgument();
    this.buildFileParseContext = ParseContext.forBaseName(target.getBaseName());
    this.ignoreFileExistenceChecks = ignoreFileExistenceChecks;

    this.resolveFilePathRelativeToBuildFileDirectoryTransform = new Function<String, String>() {
      @Override
      public String apply(String input) {
        return resolveFilePathRelativeToBuildFileDirectory(input);
      }
    };

    this.abstractBuildRuleFactoryParams = new DefaultBuildRuleBuilderParams(filesystem);
  }

  /** This is package-private so that only AbstractBuildRuleFactory can access it. */
  AbstractBuildRuleBuilderParams getAbstractBuildRuleFactoryParams() {
    return abstractBuildRuleFactoryParams;
  }

  public BuildTarget parseVisibilityTarget(String visibilityTarget)
      throws NoSuchBuildTargetException {
    return buildTargetParser.parse(visibilityTarget, visibilityParseContext);
  }

  /**
   * Convenience method that combines the result of
   * {@link #getRequiredStringAttribute(String)} with
   * {@link #resolveFilePathRelativeToBuildFileDirectory(String)}.
   */
  public Path getRequiredFileAsPathRelativeToProjectRoot(String attributeName) {
    String localPath = getRequiredStringAttribute(attributeName);
    return Paths.get(resolveFilePathRelativeToBuildFileDirectory(localPath));
  }

  /**
   * For the specified string, return a corresponding file path that is relative to the project
   * root. It is expected that {@code path} is a path relative to the directory containing the build
   * file in which it was declared. This method will also assert that the file exists.
   * <p>
   * However, if {@code path} corresponds to a generated file, then {@code path} is treated as a
   * path relative to the parallel build file directory in the generated files directory. In that
   * case, its existence will not be verified.
   */
  public String resolveFilePathRelativeToBuildFileDirectory(String path) {
    if (path.startsWith(GENFILE_PREFIX)) {
      path = path.substring(GENFILE_PREFIX.length());
      return String.format("%s/%s",
          BuckConstant.GEN_DIR,
          resolvePathAgainstBuildTargetBase(path),
          path);
    } else {
      String fullPath = resolvePathAgainstBuildTargetBase(path);
      File file = filesystem.getFileForRelativePath(fullPath);
      if (!ignoreFileExistenceChecks && !file.isFile()) {
        throw new RuntimeException("Not an ordinary file: " + fullPath);
      }

      // First, verify that the path is a descendant of the directory containing the build file.
      String basePath = target.getBasePath();
      if (!basePath.isEmpty() && !fullPath.startsWith(basePath + '/')) {
        throw new RuntimeException(file + " is not a descendant of " + target.getBasePath());
      }

      checkFullPath(fullPath);

      return fullPath;
    }
  }

  public String resolveAndCreateFilePathRelativeToBuildFileDirectory(String path) {
    if (!path.startsWith(GENFILE_PREFIX)) {
      String fullPath = resolvePathAgainstBuildTargetBase(path);
      File file = filesystem.getFileForRelativePath(fullPath);
      if (!file.isFile()) {
        try {
          file.createNewFile();
        } catch (IOException e) {
          throw new RuntimeException(String.format("Unable to create file '%s' at path '%s'",
              path, target.getBasePath()));
        }
      }
    }
    return resolveFilePathRelativeToBuildFileDirectory(path);
  }

  private void checkFullPath(String fullPath) {
    if (fullPath.contains("..")) {
      throw new HumanReadableException(
          "\"%s\" in target \"%s\" refers to a parent directory.",
          fullPath,
          target.getFullyQualifiedName());
    }

    String ancestor = buildFiles.getBasePathOfAncestorTarget(fullPath);
    if (!target.getBasePath().equals(ancestor)) {
      throw new HumanReadableException(
          "\"%s\" in target \"%s\" crosses a buck package boundary. Find the nearest BUCK file " +
          "in the directory containing this file and refer to the rule referencing the " +
          "desired file.",
          fullPath,
          target);
    }
  }

  public File resolveDirectoryRelativeToBuildFileDirectory(String path) {
    return new File(resolvePathAgainstBuildTargetBase(path));
  }

  public String resolveDirectoryPathRelativeToBuildFileDirectory(String path) {
    Preconditions.checkNotNull(path);
    Preconditions.checkArgument(!path.startsWith(GENFILE_PREFIX));

    String fullPath = resolvePathAgainstBuildTargetBase(path);

    checkFullPath(fullPath);

    if (!ignoreFileExistenceChecks && !new File(fullPath).isDirectory()) {
      throw new RuntimeException("Not a directory: " + fullPath);
    }

    return fullPath;
  }

  private String resolvePathAgainstBuildTargetBase(String path) {
    if (target.getBasePath().isEmpty()) {
      return path;
    } else {
      return String.format("%s/%s", target.getBasePath(), path);
    }
  }

  /**
   * @param resource that identifies either a file path or a build target.
   * @param builder If {@code resource} corresponds to a {@link BuildTarget}, that build target will
   *     be added to the {@code deps} of this builder.
   * @return a source path that corresponds to the specified {@code resource}.
   */
  public SourcePath asSourcePath(String resource, AbstractBuildRuleBuilder<?> builder) {
    // TODO(simons): Don't hard code this check for built-target-ism
    if (resource.startsWith(BuildTarget.BUILD_TARGET_PREFIX) || resource.charAt(0) == ':') {
      BuildTarget buildTarget;
      try {
        buildTarget = resolveBuildTarget(resource);
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(
            "Unable to find build target '%s' while parsing definition of %s", resource, target);
      }
      builder.addDep(buildTarget);
      return new BuildTargetSourcePath(buildTarget);
    } else {
      String relativePath = resolveFilePathRelativeToBuildFileDirectory(resource);
      return new FileSourcePath(relativePath);
    }
  }

  /**
   * If there is a string value associated with the specified {@code identifier}, this returns a
   * {@link SourcePath} that corresponds to that value.
   * @param identifier for the argument in this params.
   * @param builder If {@code resource} corresponds to a {@link BuildTarget}, that build target will
   *     be added to the {@code deps} of this builder.
   * @return a source path that corresponds to the specified {@code identifier}.
   */
  public Optional<SourcePath> getOptionalSourcePath(String identifier,
      AbstractBuildRuleBuilder<?> builder) {
    Optional<String> option = getOptionalStringAttribute(identifier);
    if (option.isPresent()) {
      SourcePath sourcePath = asSourcePath(option.get(), builder);
      return Optional.of(sourcePath);
    } else {
      return Optional.absent();
    }
  }

  public BuildTarget getRequiredBuildTarget(String attributeName) {
    String buildTarget = getRequiredStringAttribute(attributeName);
    try {
      return resolveBuildTarget(buildTarget);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException("Couldn't resolve build target for %s=%s in %s.",
          attributeName,
          buildTarget,
          this.target.getFullyQualifiedName());
    }
  }

  public BuildTarget resolveBuildTarget(String target) throws NoSuchBuildTargetException {
    return buildTargetParser.parse(target, buildFileParseContext);
  }

  public String getRequiredStringAttribute(String attributeName) {
    Optional<String> value = getOptionalStringAttribute(attributeName);
    if (value.isPresent()) {
      return value.get();
    } else {
      throw new RuntimeException(
          String.format("%s is missing required attribute: %s", target, attributeName));
    }
  }

  public Optional<String> getOptionalStringAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value != null) {
      if (value instanceof String) {
        return Optional.of((String)value);
      } else {
        throw new RuntimeException(String.format("Expected a string for %s in %s but was %s",
            attributeName,
            target.getBuildFile().getPath(),
            value));
      }
    } else {
      return Optional.absent();
    }
  }

  public boolean hasAttribute(String attributeName) {
    return instance.containsKey(attributeName);
  }

  public Function<String, String> getResolveFilePathRelativeToBuildFileDirectoryTransform() {
    return resolveFilePathRelativeToBuildFileDirectoryTransform;
  }

  /** If a boolean attribute has not been specified, then it always defaults to false. */
  public boolean getBooleanAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value == null) {
      return false;
    } else if (value instanceof Boolean) {
      return (Boolean)value;
    } else {
      throw new RuntimeException(String.format(
          "Value for %s in %s must be either True or False",
          attributeName,
          target.getFullyQualifiedName()));
    }
  }

  /** @return non-null list that may be empty */
  @SuppressWarnings("unchecked")
  public List<String> getOptionalListAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value != null) {
      if (value instanceof List) {
        return (List<String>)value;
      } else {
        throw new RuntimeException(String.format("Expected an array for %s in %s but was %s",
            attributeName,
            target.getBuildFile().getPath(),
            value));
      }
    } else {
      return ImmutableList.of();
    }
  }

  /**
   * This is similar to {@link #getOptionalListAttribute(String)}, except if the value associated
   * with {@code attributeName} is {@code null}, then this method actually returns {@code null}
   * rather than an empty list.
   */
  @Nullable
  public List<String> getNullableListAttribute(String attributeName) {
    Object value = instance.get(attributeName);
    if (value != null) {
      return getOptionalListAttribute(attributeName);
    } else {
      return null;
    }
  }
}
