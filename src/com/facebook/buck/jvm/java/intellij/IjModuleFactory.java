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

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaBinaryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.kotlin.KotlinLibraryDescription;
import com.facebook.buck.jvm.kotlin.KotlinTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds {@link IjModule}s out of {@link TargetNode}s.
 */
public class IjModuleFactory {

  /**
   * These target types are mapped onto .iml module files.
   */
  public static final ImmutableSet<Class<? extends Description<?>>>
      SUPPORTED_MODULE_DESCRIPTION_CLASSES = ImmutableSet.of(
          AndroidBinaryDescription.class,
          AndroidLibraryDescription.class,
          AndroidResourceDescription.class,
          CxxLibraryDescription.class,
          JavaBinaryDescription.class,
          JavaLibraryDescription.class,
          JavaTestDescription.class,
          RobolectricTestDescription.class,
          GroovyLibraryDescription.class,
          GroovyTestDescription.class,
          KotlinLibraryDescription.class,
          KotlinTestDescription.class);

  /**
   * Rule describing which aspects of the supplied {@link TargetNode} to transfer to the
   * {@link IjModule} being constructed.
   *
   * @param <T> TargetNode type.
   */
  private interface IjModuleRule<T> {
    Class<? extends Description<?>> getDescriptionClass();
    void apply(TargetNode<T, ?> targetNode, ModuleBuildContext context);
  }

  // From constructor of com.intellij.openapi.projectRoots.impl.JavaSdkImpl
  private static final String SDK_TYPE_JAVA = "JavaSDK";

  // From constructor of org.jetbrains.android.sdk.AndroidSdkType
  private static final String SDK_TYPE_ANDROID = "Android SDK";

  // From constructor of org.jetbrains.idea.devkit.projectRoots.IdeaJdk
  private static final String SDK_TYPE_IDEA = "IDEA JDK";

  private final ProjectFilesystem projectFilesystem;
  private final Map<Class<? extends Description<?>>, IjModuleRule<?>> moduleRuleIndex =
      new HashMap<>();
  private final IjModuleFactoryResolver moduleFactoryResolver;
  private final IjProjectConfig projectConfig;
  private final boolean excludeShadows;
  private final boolean autogenerateAndroidFacetSources;

  /**
   * @param moduleFactoryResolver see {@link IjModuleFactoryResolver}.
   */
  public IjModuleFactory(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig,
      boolean excludeShadows) {
    this.projectFilesystem = projectFilesystem;
    this.excludeShadows = excludeShadows;
    this.projectConfig = projectConfig;
    this.autogenerateAndroidFacetSources = projectConfig.isAutogenerateAndroidFacetSourcesEnabled();

    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new CxxLibraryModuleRule());
    addToIndex(new JavaBinaryModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
    addToIndex(new RobolectricTestModuleRule());
    addToIndex(new GroovyLibraryModuleRule());
    addToIndex(new GroovyTestModuleRule());
    addToIndex(new KotlinLibraryModuleRule());
    addToIndex(new KotlinTestModuleRule());

    this.moduleFactoryResolver = moduleFactoryResolver;

    Preconditions.checkState(
        moduleRuleIndex.keySet().equals(SUPPORTED_MODULE_DESCRIPTION_CLASSES));
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getDescriptionClass()));
    Preconditions.checkArgument(SUPPORTED_MODULE_DESCRIPTION_CLASSES.contains(
        rule.getDescriptionClass()));
    moduleRuleIndex.put(rule.getDescriptionClass(), rule);
  }

  public boolean isInRootCell(TargetNode<?, ?> targetNode) {
    return targetNode.getFilesystem().equals(projectFilesystem);
  }

  /**
   * Create an {@link IjModule} form the supplied parameters.
   *
   * @param moduleBasePath the top-most directory the module is responsible for.
   * @param targetNodes set of nodes the module is to be created from.
   * @return nice shiny new module.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public IjModule createModule(
      Path moduleBasePath,
      ImmutableSet<TargetNode<?, ?>> targetNodes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());


    ImmutableSet<BuildTarget> moduleBuildTargets = targetNodes.stream()
        .map(TargetNode::getBuildTarget)
        .collect(MoreCollectors.toImmutableSet());

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    for (TargetNode<?, ?> targetNode : targetNodes) {
      IjModuleRule<?> rule = Preconditions.checkNotNull(moduleRuleIndex.get(
          targetNode.getDescription().getClass()));
      rule.apply((TargetNode) targetNode, context);
    }

    Optional<String> sourceLevel = getSourceLevel(targetNodes);
    String sdkType;
    Optional<String> sdkName;
    IjModuleType moduleType = context.getModuleType().orElse(IjModuleType.DEFAULT);
    Optional<Path> metaInfDirectory = Optional.empty();

    if (moduleType == IjModuleType.PLUGIN_MODULE) {
      sdkType = SDK_TYPE_IDEA;
      sdkName = projectConfig.getIntellijModuleSdkName();
      metaInfDirectory = context.getMetaInfDirectory();
    } else if (context.isAndroidFacetBuilderPresent()) {
      context.getOrCreateAndroidFacetBuilder().setGeneratedSourcePath(
          createAndroidGenPath(moduleBasePath));

      sdkType = projectConfig.getAndroidModuleSdkType().orElse(SDK_TYPE_ANDROID);
      sdkName = projectConfig.getAndroidModuleSdkName();
    } else {
      sdkType = projectConfig.getJavaModuleSdkType().orElse(SDK_TYPE_JAVA);
      sdkName = projectConfig.getJavaModuleSdkName();
    }

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .addAllGeneratedSourceCodeFolders(context.getGeneratedSourceCodeFolders())
        .setSdkName(sdkName)
        .setSdkType(sdkType)
        .setLanguageLevel(sourceLevel)
        .setModuleType(moduleType)
        .setMetaInfDirectory(metaInfDirectory)
        .build();
  }

  private Path createAndroidGenPath(Path moduleBasePath) {
    return Paths
        .get(IjAndroidHelper.getAndroidGenDir(projectFilesystem))
        .resolve(moduleBasePath)
        .resolve("gen");
  }

  private Optional<String> getSourceLevel(
      Iterable<TargetNode<?, ?>> targetNodes) {
    Optional<String> result = Optional.empty();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      if (!(targetNode.getDescription() instanceof JavaLibraryDescription)) {
        continue;
      }

      JavacOptions defaultJavacOptions = projectConfig.getJavaBuckConfig().getDefaultJavacOptions();
      String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
      String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
      JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) targetNode.getConstructorArg();
      if (!defaultSourceLevel.equals(arg.source.orElse(defaultSourceLevel)) ||
          !defaultTargetLevel.equals(arg.target.orElse(defaultTargetLevel))) {
        result = arg.source;
      }
    }

    if (result.isPresent()) {
      result = Optional.of(JavaLanguageLevelHelper.normalizeSourceLevel(result.get()));
    }

    return result;
  }

  /**
   * Calculate the set of directories containing inputs to the target.
   *
   * @param paths inputs to a given target.
   * @return index of path to set of inputs in that path
   */
  private static ImmutableMultimap<Path, Path> getSourceFoldersToInputsIndex(
      ImmutableSet<Path> paths) {
    Path defaultParent = Paths.get("");
    return paths
        .stream()
        .collect(
            MoreCollectors.toImmutableMultimap(
                path -> {
                  Path parent = path.getParent();
                  return parent == null ? defaultParent : parent;
                },
                path -> path)
        );
  }

  /**
   * @param paths paths to check
   * @return whether any of the paths pointed to something not in the source tree.
   */
  private static boolean containsNonSourcePath(Collection<SourcePath> paths) {
    return paths.stream().anyMatch(path -> !(path instanceof PathSourcePath));
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param foldersToInputsIndex mapping of source folders to their inputs.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This
   *                           only makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  private static void addSourceFolders(
      IJFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
              entry.getKey(),
              wantsPackagePrefix,
              ImmutableSortedSet.copyOf(Ordering.natural(), entry.getValue())
          )
      );
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<?, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> inputPaths
  ) {
    ImmutableMultimap<Path, Path> foldersToInputsIndex = getSourceFoldersToInputsIndex(inputPaths);
    addSourceFolders(folderFactory, foldersToInputsIndex, wantsPackagePrefix, context);
    addDeps(foldersToInputsIndex, targetNode, dependencyType, context);

    addGeneratedOutputIfNeeded(folderFactory, targetNode, context);

    if (targetNode.getConstructorArg() instanceof JvmLibraryArg) {
      addAnnotationOutputIfNeeded(folderFactory, targetNode, context);
    }
  }

  private void addDepsAndFolder(
      IJFolderFactory folderFactory,
      DependencyType dependencyType,
      TargetNode<?, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context
  ) {
    addDepsAndFolder(
        folderFactory,
        dependencyType,
        targetNode,
        wantsPackagePrefix,
        context,
        targetNode.getInputs());
  }

  private void addDepsAndSources(
      TargetNode<?, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        SourceFolder.FACTORY,
        DependencyType.PROD,
        targetNode,
        wantsPackagePrefix,
        context);
  }

  private void addDepsAndTestSources(
      TargetNode<?, ?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        TestFolder.FACTORY,
        DependencyType.TEST,
        targetNode,
        wantsPackagePrefix,
        context);
  }

  private static void addDeps(
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      TargetNode<?, ?> targetNode,
      DependencyType dependencyType,
      ModuleBuildContext context) {
    context.addDeps(
        foldersToInputsIndex.keySet(),
        targetNode.getBuildDeps(),
        dependencyType);
  }

  private <T extends JavaLibraryDescription.Arg> void addCompiledShadowIfNeeded(
      TargetNode<T, ?> targetNode,
      ModuleBuildContext context) {
    if (excludeShadows) {
      return;
    }

    T arg = targetNode.getConstructorArg();
    // TODO(mkosiba): investigate supporting annotation processors without resorting to this.
    boolean hasAnnotationProcessors = !arg.annotationProcessors.isEmpty();
    if (containsNonSourcePath(arg.srcs) || hasAnnotationProcessors) {
      context.addCompileShadowDep(targetNode.getBuildTarget());
    }
  }

  @SuppressWarnings("unchecked")
  private void addAnnotationOutputIfNeeded(
      IJFolderFactory folderFactory,
      TargetNode<?, ?> targetNode,
      ModuleBuildContext context) {
    TargetNode<? extends JvmLibraryArg, ?> jvmLibraryTargetNode =
        (TargetNode<? extends JvmLibraryArg, ?>) targetNode;

    Optional<Path> annotationOutput =
        moduleFactoryResolver.getAnnotationOutputPath(jvmLibraryTargetNode);
    if (!annotationOutput.isPresent()) {
      return;
    }

    Path annotationOutputPath = annotationOutput.get();
    context.addGeneratedSourceCodeFolder(
        folderFactory.create(
            annotationOutputPath,
            false,
            ImmutableSortedSet.of(annotationOutputPath))
    );
  }

  private void addGeneratedOutputIfNeeded(
      IJFolderFactory folderFactory,
      TargetNode<?, ?> targetNode,
      ModuleBuildContext context) {

    Set<Path> generatedSourcePaths = findConfiguredGeneratedSourcePaths(targetNode);

    for (Path generatedSourcePath : generatedSourcePaths) {
      context.addGeneratedSourceCodeFolder(
          folderFactory.create(
              generatedSourcePath,
              false,
              ImmutableSortedSet.of(generatedSourcePath))
      );
    }
  }

  private Set<Path> findConfiguredGeneratedSourcePaths(TargetNode<?, ?> targetNode) {
    ImmutableMap<String, String> depToGeneratedSourcesMap =
        projectConfig.getDepToGeneratedSourcesMap();
    BuildTarget buildTarget = targetNode.getBuildTarget();

    Set<Path> generatedSourcePaths = new HashSet<>();

    for (BuildTarget dependencyTarget : targetNode.getBuildDeps()) {
      String buildTargetName = dependencyTarget.toString();
      String generatedSourceWithPattern = depToGeneratedSourcesMap.get(buildTargetName);
      if (generatedSourceWithPattern != null) {
        String generatedSource = generatedSourceWithPattern.replaceAll(
            "%name%",
            buildTarget.getShortNameAndFlavorPostfix());
        Path generatedSourcePath = BuildTargets.getGenPath(
            projectFilesystem,
            buildTarget,
            generatedSource);

        generatedSourcePaths.add(generatedSourcePath);
      }
    }

    return generatedSourcePaths;
  }

  private class AndroidBinaryModuleRule
      implements IjModuleRule<AndroidBinaryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidBinaryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<AndroidBinaryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      context.addDeps(target.getBuildDeps(), DependencyType.PROD);

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      androidFacetBuilder
          .setManifestPath(moduleFactoryResolver.getAndroidManifestPath(target))
          .setProguardConfigPath(moduleFactoryResolver.getProguardConfigPath(target))
          .setAutogenerateSources(autogenerateAndroidFacetSources)
          .setAndroidLibrary(false);
    }
  }

  private class AndroidLibraryModuleRule
      implements IjModuleRule<AndroidLibraryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidLibraryDescription.class;
    }

    @Override
    public void apply(TargetNode<AndroidLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }

      IjModuleAndroidFacet.Builder builder = context.getOrCreateAndroidFacetBuilder();
      Optional<Path> manifestPath = moduleFactoryResolver.getLibraryAndroidManifestPath(target);
      if (manifestPath.isPresent()) {
        builder.setManifestPath(manifestPath.get());
      }
      builder.setAutogenerateSources(autogenerateAndroidFacetSources);
      builder.setAndroidLibrary(true);
    }
  }

  private class AndroidResourceModuleRule
      implements IjModuleRule<AndroidResourceDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return AndroidResourceDescription.class;
    }

    @Override
    public void apply(
        TargetNode<AndroidResourceDescription.Arg, ?> target,
        ModuleBuildContext context) {

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      androidFacetBuilder
          .setAutogenerateSources(autogenerateAndroidFacetSources)
          .setAndroidLibrary(true);

      Optional<Path> assets = moduleFactoryResolver.getAssetsPath(target);
      if (assets.isPresent()) {
        androidFacetBuilder.addAssetPaths(assets.get());
      }

      Optional<Path> resources = moduleFactoryResolver.getAndroidResourcePath(target);
      ImmutableSet<Path> resourceFolders;
      if (resources.isPresent()) {
        resourceFolders = ImmutableSet.of(resources.get());

        androidFacetBuilder.addAllResourcePaths(resourceFolders);

        List<String> excludedResourcePaths = projectConfig.getExcludedResourcePaths();

        for (Path resourceFolder : resourceFolders) {
          context.addSourceFolder(
              new AndroidResourceFolder(resourceFolder)
          );

          excludedResourcePaths
              .stream()
              .map((file) -> resourceFolder.resolve(file))
              .forEach((folder) -> context.addSourceFolder(new ExcludeFolder(folder)));
        }
      } else {
        resourceFolders = ImmutableSet.of();
      }

      androidFacetBuilder.setPackageName(target.getConstructorArg().rDotJavaPackage);

      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }
      context.getOrCreateAndroidFacetBuilder().setAndroidLibrary(true);

      context.addDeps(resourceFolders, target.getBuildDeps(), DependencyType.PROD);
    }
  }

  private class CxxLibraryModuleRule implements IjModuleRule<CxxLibraryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return CxxLibraryDescription.class;
    }

    @Override
    public void apply(TargetNode<CxxLibraryDescription.Arg, ?> target, ModuleBuildContext context) {
      addSourceFolders(
          SourceFolder.FACTORY,
          getSourceFoldersToInputsIndex(target.getInputs()),
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class JavaBinaryModuleRule
      implements IjModuleRule<JavaBinaryDescription.Args> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaBinaryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<JavaBinaryDescription.Args, ?> target,
        ModuleBuildContext context) {
      context.addDeps(target.getBuildDeps(), DependencyType.PROD);
      // If this is a binary based on an intellij provided library *and*
      // it has a meta_inf_directory, then it's almost certainly an IntelliJ
      // plugin, so mark the module as such.  This will allow users to create
      // a "plugin" runtime configurations for that module in IntelliJ.
      Set<String> intellijLibraries = projectConfig.getIntellijSdkTargets();
      for (BuildTarget dep : target.getBuildDeps()) {
        Optional<Path> metaInfDirectory = target.getConstructorArg().metaInfDirectory;
        if (metaInfDirectory.isPresent() &&
            intellijLibraries.contains(dep.getFullyQualifiedName())) {
          context.setIsIntellijPlugin(metaInfDirectory.get());
          break;
        }
      }
    }

  }

  private class JavaLibraryModuleRule implements IjModuleRule<JavaLibraryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<JavaLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private class GroovyLibraryModuleRule implements IjModuleRule<GroovyLibraryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return GroovyLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<GroovyLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class GroovyTestModuleRule implements IjModuleRule<GroovyTestDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return GroovyTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<GroovyTestDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class JavaTestModuleRule implements IjModuleRule<JavaTestDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return JavaTestDescription.class;
    }

    @Override
    public void apply(TargetNode<JavaTestDescription.Arg, ?> target, ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private class KotlinLibraryModuleRule implements IjModuleRule<KotlinLibraryDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return KotlinLibraryDescription.class;
    }

    @Override
    public void apply(
        TargetNode<KotlinLibraryDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class KotlinTestModuleRule implements IjModuleRule<KotlinTestDescription.Arg> {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return KotlinTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<KotlinTestDescription.Arg, ?> target,
        ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class RobolectricTestModuleRule extends JavaTestModuleRule {

    @Override
    public Class<? extends Description<?>> getDescriptionClass() {
      return RobolectricTestDescription.class;
    }

    @Override
    public void apply(
        TargetNode<JavaTestDescription.Arg, ?> target, ModuleBuildContext context) {
      super.apply(target, context);

      context.getOrCreateAndroidFacetBuilder()
          .setAutogenerateSources(autogenerateAndroidFacetSources)
          .setAndroidLibrary(true);
    }
  }
}
