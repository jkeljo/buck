/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;

import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Writes the serialized representations of IntelliJ project components to disk.
 */
public class IjProjectWriter {

  public static final char DELIMITER = '%';
  public static final Path IDEA_CONFIG_DIR_PREFIX = Paths.get(".idea");
  public static final Path LIBRARIES_PREFIX = IDEA_CONFIG_DIR_PREFIX.resolve("libraries");
  public static final Path MODULES_PREFIX = IDEA_CONFIG_DIR_PREFIX.resolve("modules");

  private enum StringTemplateFile {
    MODULE_TEMPLATE("ij-module.st"),
    MODULE_INDEX_TEMPLATE("ij-module-index.st"),
    MISC_TEMPLATE("ij-misc.st"),
    LIBRARY_TEMPLATE("ij-library.st"),
    GENERATED_BY_IDEA_CLASS("GeneratedByIdeaClass.st");

    private final String fileName;
    StringTemplateFile(String fileName) {
      this.fileName = fileName;
    }

    public String getFileName() {
      return fileName;
    }
  }

  private IjProjectTemplateDataPreparer projectDataPreparer;
  private IjProjectConfig projectConfig;
  private ProjectFilesystem projectFilesystem;
  private IjModuleGraph moduleGraph;

  public IjProjectWriter(
      IjProjectTemplateDataPreparer projectDataPreparer,
      IjProjectConfig projectConfig,
      ProjectFilesystem projectFilesystem,
      IjModuleGraph moduleGraph) {
    this.projectDataPreparer = projectDataPreparer;
    this.projectConfig = projectConfig;
    this.projectFilesystem = projectFilesystem;
    this.moduleGraph = moduleGraph;
  }

  public void write(
      boolean runPostGenerationCleaner,
      boolean removeUnusedLibraries) throws IOException {
    IJProjectCleaner cleaner = new IJProjectCleaner(projectFilesystem);

    writeProjectSettings(cleaner, projectConfig);

    boolean generateClasses = !projectConfig.isAutogenerateAndroidFacetSourcesEnabled();

    for (IjModule module : projectDataPreparer.getModulesToBeWritten()) {
      Path generatedModuleFile = writeModule(module);
      cleaner.doNotDelete(generatedModuleFile);

      if (generateClasses) {
        writeClassesGeneratedByIdea(module, cleaner);
      }
    }
    for (IjLibrary library : projectDataPreparer.getLibrariesToBeWritten()) {
      Path generatedLibraryFile = writeLibrary(library);
      cleaner.doNotDelete(generatedLibraryFile);
    }
    Path indexFile = writeModulesIndex();
    cleaner.doNotDelete(indexFile);

    cleaner.clean(
        projectConfig.getBuckConfig(),
        LIBRARIES_PREFIX,
        runPostGenerationCleaner,
        removeUnusedLibraries);
  }

  private Path writeModule(IjModule module) throws IOException {
    projectFilesystem.mkdirs(MODULES_PREFIX);
    Path path = module.getModuleImlFilePath();

    ST moduleContents = getST(StringTemplateFile.MODULE_TEMPLATE);

    moduleContents.add(
        "contentRoot",
        projectDataPreparer.getContentRoot(module));
    moduleContents.add(
        "dependencies",
        projectDataPreparer.getDependencies(module));
    moduleContents.add(
        "generatedSourceFolders",
        projectDataPreparer.getGeneratedSourceFolders(module));
    moduleContents.add(
        "androidFacet",
        projectDataPreparer.getAndroidProperties(module));
    moduleContents.add(
        "sdk",
        module.getSdkName().orElse(null));
    moduleContents.add(
        "sdkType",
        module.getSdkType().orElse(null));
    moduleContents.add(
        "languageLevel",
        JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(
            module.getLanguageLevel().orElse(null)));
    moduleContents.add(
        "moduleType",
        module.getModuleType().orElse(IjModuleType.DEFAULT));
    moduleContents.add(
        "metaInfDirectory",
        module.getMetaInfDirectory()
            .map((dir) -> module.getModuleBasePath().relativize(dir))
            .orElse(null));

    writeToFile(moduleContents, path);
    return path;
  }

  private void writeProjectSettings(
      IJProjectCleaner cleaner,
      IjProjectConfig projectConfig) throws IOException {

    Optional<String> sdkName = projectConfig.getProjectJdkName();
    Optional<String> sdkType = projectConfig.getProjectJdkType();

    if (!sdkName.isPresent() || !sdkType.isPresent()) {
      return;
    }

    projectFilesystem.mkdirs(IDEA_CONFIG_DIR_PREFIX);

    Path path = IDEA_CONFIG_DIR_PREFIX.resolve("misc.xml");

    ST contents = getST(StringTemplateFile.MISC_TEMPLATE);

    String languageLevelInIjFormat = getLanguageLevelFromConfig();

    contents.add("languageLevel", languageLevelInIjFormat);
    contents.add("jdk15", getJdk15FromLanguageLevel(languageLevelInIjFormat));
    contents.add("jdkName", sdkName.get());
    contents.add("jdkType", sdkType.get());

    writeToFile(contents, path);

    cleaner.doNotDelete(path);
  }

  private String getLanguageLevelFromConfig() {
    Optional<String> languageLevelFromConfig = projectConfig.getProjectLanguageLevel();
    if (languageLevelFromConfig.isPresent()) {
      return languageLevelFromConfig.get();
    } else {
      String languageLevel = projectConfig
          .getJavaBuckConfig()
          .getDefaultJavacOptions()
          .getSourceLevel();
      return JavaLanguageLevelHelper.convertLanguageLevelToIjFormat(languageLevel);
    }
  }

  private static boolean getJdk15FromLanguageLevel(String languageLevel) {
    boolean jdkUnder15 = "JDK_1_3".equals(languageLevel) || "JDK_1_4".equals(languageLevel);
    return !jdkUnder15;
  }

  private Path writeLibrary(IjLibrary library) throws IOException {
    projectFilesystem.mkdirs(LIBRARIES_PREFIX);
    Path path = LIBRARIES_PREFIX.resolve(library.getName() + ".xml");

    ST contents = getST(StringTemplateFile.LIBRARY_TEMPLATE);
    contents.add("name", library.getName());
    contents.add(
        "binaryJar",
        library.getBinaryJar().map(MorePaths::pathWithUnixSeparators).orElse(null));
    contents.add(
        "classPaths",
        library.getClassPaths().stream()
            .map(MorePaths::pathWithUnixSeparators)
            .collect(MoreCollectors.toImmutableSet()));
    contents.add(
        "sourceJar",
        library.getSourceJar().map(MorePaths::pathWithUnixSeparators).orElse(null));
    contents.add("javadocUrl", library.getJavadocUrl().orElse(null));
    //TODO(mkosiba): support res and assets for aar.

    writeToFile(contents, path);
    return path;
  }

  private Path writeModulesIndex() throws IOException {
    projectFilesystem.mkdirs(IDEA_CONFIG_DIR_PREFIX);
    Path path = IDEA_CONFIG_DIR_PREFIX.resolve("modules.xml");

    ST moduleIndexContents = getST(StringTemplateFile.MODULE_INDEX_TEMPLATE);
    moduleIndexContents.add("modules", projectDataPreparer.getModuleIndexEntries());

    writeToFile(moduleIndexContents, path);
    return path;
  }

  private void writeClassesGeneratedByIdea(
      IjModule module,
      IJProjectCleaner cleaner) throws IOException {
    Optional<IjModuleAndroidFacet> androidFacet = module.getAndroidFacet();
    if (!androidFacet.isPresent()) {
      return;
    }

    Optional<String> packageName = getResourcePackage(module, androidFacet.get());
    if (!packageName.isPresent()) {
      return;
    }

    writeGeneratedByIdeaClassToFile(
        androidFacet.get(),
        cleaner,
        packageName.get(),
        "BuildConfig",
        "  public final static boolean DEBUG = Boolean.parseBoolean(null);");

    writeGeneratedByIdeaClassToFile(
        androidFacet.get(),
        cleaner,
        packageName.get(),
        "R",
        null);

    writeGeneratedByIdeaClassToFile(
        androidFacet.get(),
        cleaner,
        packageName.get(),
        "Manifest",
        null);
  }

  private void writeGeneratedByIdeaClassToFile(
      IjModuleAndroidFacet androidFacet,
      IJProjectCleaner cleaner,
      String packageName,
      String className,
      @Nullable String content) throws IOException {

    ST contents = getST(StringTemplateFile.GENERATED_BY_IDEA_CLASS)
        .add("package", packageName)
        .add("className", className)
        .add("content", content);

    Path fileToWrite = androidFacet
        .getGeneratedSourcePath()
        .resolve(packageName.replace(".", "/"))
        .resolve(className + ".java");

    cleaner.doNotDelete(fileToWrite);

    writeToFile(contents, fileToWrite);
  }

  private Optional<String> getResourcePackage(
      IjModule module,
      IjModuleAndroidFacet androidFacet) {
    Optional<String> packageName = androidFacet.getPackageName();
    if (!packageName.isPresent()) {
      packageName = getFirstResourcePackageFromDependencies(module);
    }
    return packageName;
  }

  private Optional<String> getFirstResourcePackageFromDependencies(IjModule module) {
    ImmutableMap<IjModule, DependencyType> deps =
        moduleGraph.getDependentModulesFor(module);
    for (IjModule dep : deps.keySet()) {
      Optional<IjModuleAndroidFacet> facet = dep.getAndroidFacet();
      if (facet.isPresent()) {
        Optional<String> packageName = facet.get().getPackageName();
        if (packageName.isPresent()) {
          return packageName;
        }
      }
    }
    return Optional.empty();
  }

  private static ST getST(StringTemplateFile file) throws IOException {
    URL templateUrl = Resources.getResource(IjProjectWriter.class, file.getFileName());
    String template = Resources.toString(templateUrl, StandardCharsets.UTF_8);
    return new ST(template, DELIMITER, DELIMITER);
  }

  @VisibleForTesting
  protected void writeToFile(ST contents, Path path) throws IOException {
    StringWriter stringWriter = new StringWriter();
    AutoIndentWriter noIndentWriter = new AutoIndentWriter(stringWriter);
    contents.write(noIndentWriter);
    byte[] renderedContentsBytes = noIndentWriter.toString().getBytes(StandardCharsets.UTF_8);
    if (projectFilesystem.exists(path)) {
      Sha1HashCode fileSha1 = projectFilesystem.computeSha1(path);
      Sha1HashCode contentsSha1 = Sha1HashCode.fromHashCode(Hashing.sha1()
          .hashBytes(renderedContentsBytes));
      if (fileSha1.equals(contentsSha1)) {
        return;
      }
    }

    boolean danglingTempFile = false;
    Path tempFile = projectFilesystem.createTempFile(
        IDEA_CONFIG_DIR_PREFIX,
        path.getFileName().toString(),
        ".tmp");
    try {
      danglingTempFile = true;
      try (OutputStream outputStream = projectFilesystem.newFileOutputStream(tempFile)) {
        outputStream.write(contents.render().getBytes());
      }
      projectFilesystem.createParentDirs(path);
      projectFilesystem.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
      danglingTempFile = false;
    } finally {
      if (danglingTempFile) {
        projectFilesystem.deleteFileAtPath(tempFile);
      }
    }
  }
}
