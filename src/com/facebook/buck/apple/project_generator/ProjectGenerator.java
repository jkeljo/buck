/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.project_generator;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDebugFormat;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleHeaderVisibilities;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleResources;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.HasAppleBundleFields;
import com.facebook.buck.apple.InfoPlistSubstitution;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.SceneKitAssetsDescription;
import com.facebook.buck.apple.XcodePostbuildScriptDescription;
import com.facebook.buck.apple.XcodePrebuildScriptDescription;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCConfigurationList;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideCompile;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.MoreProjectFilesystems;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.StringWithMacrosArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.PackagedResource;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.stringtemplate.v4.ST;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generator for xcode project and associated files from a set of xcode/ios rules.
 */
public class ProjectGenerator {
  public static final String BUILD_WITH_BUCK_POSTFIX = "-Buck";

  private static final Logger LOG = Logger.get(ProjectGenerator.class);
  private static final String BUILD_WITH_BUCK_TEMPLATE = "build-with-buck.st";
  private static final String BUILD_WITH_BUCK_PY_TEMPLATE = "build_with_buck.py";
  private static final String FIX_UUID_PY_RESOURCE = "fix_uuid.py";
  private static final String CODESIGN_TEMPLATE = "codesign.st";
  private static final String CODESIGN_PY_RESOURCE = "codesign.py";
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  public static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  public static final String SHOW_OUTPUT = "--show-output";
  public static final String PRODUCT_NAME = "PRODUCT_NAME";

  private static final ImmutableSet<Class<? extends Description<?>>>
      APPLE_NATIVE_DESCRIPTION_CLASSES = ImmutableSet.of(
          AppleBinaryDescription.class,
          AppleLibraryDescription.class,
          CxxLibraryDescription.class);

  private static final ImmutableSet<AppleBundleExtension> APPLE_NATIVE_BUNDLE_EXTENSIONS =
      ImmutableSet.of(
          AppleBundleExtension.APP,
          AppleBundleExtension.FRAMEWORK);

  public enum Option {
    /** Use short BuildTarget name instead of full name for targets */
    USE_SHORT_NAMES_FOR_TARGETS,

    /** Put targets into groups reflecting directory structure of their BUCK files */
    CREATE_DIRECTORY_STRUCTURE,

    /** Generate read-only project files */
    GENERATE_READ_ONLY_FILES,

    /** Include tests that test root targets in the scheme */
    INCLUDE_TESTS,

    /** Include dependencies tests in the scheme */
    INCLUDE_DEPENDENCIES_TESTS,

    /** Don't use header maps as header search paths */
    DISABLE_HEADER_MAPS,

    /**
     * Generate one header map containing all the headers it's using and reference only this
     * header map in the header search paths.
     */
    MERGE_HEADER_MAPS,

    /** Generates only headers symlink trees. */
    GENERATE_HEADERS_SYMLINK_TREES_ONLY,
  }

  /**
   * Standard options for generating a separated project
   */
  public static final ImmutableSet<Option> SEPARATED_PROJECT_OPTIONS = ImmutableSet.of(
      Option.USE_SHORT_NAMES_FOR_TARGETS);

  /**
   * Standard options for generating a combined project
   */
  public static final ImmutableSet<Option> COMBINED_PROJECT_OPTIONS = ImmutableSet.of(
      Option.CREATE_DIRECTORY_STRUCTURE,
      Option.USE_SHORT_NAMES_FOR_TARGETS);

  private static final FileAttribute<?> READ_ONLY_FILE_ATTRIBUTE =
      PosixFilePermissions.asFileAttribute(
          ImmutableSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));

  private final TargetGraph targetGraph;
  private final AppleDependenciesCache dependenciesCache;
  private final Cell projectCell;
  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final PathRelativizer pathRelativizer;

  private final String buildFileName;
  private final ImmutableSet<Option> options;
  private final Optional<BuildTarget> targetToBuildWithBuck;
  private final ImmutableList<String> buildWithBuckFlags;
  private final ExecutableFinder executableFinder;
  private final ImmutableMap<String, String> environment;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<TargetNode<?, ?>, Optional<PBXTarget>> targetNodeToProjectTarget;
  private final ImmutableMultimap.Builder<TargetNode<?, ?>, PBXTarget>
      targetNodeToGeneratedProjectTargetBuilder;
  private boolean projectGenerated;
  private final List<Path> headerSymlinkTrees;
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final Function<? super TargetNode<?, ?>, BuildRuleResolver> buildRuleResolverForNode;
  private final BuildRuleResolver defaultBuildRuleResolver;
  private final SourcePathResolver defaultPathResolver;
  private final BuckEventBus buckEventBus;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set.
   */
  private final ImmutableSet.Builder<String> targetConfigNamesBuilder;

  private final Map<String, String> gidsToTargetNames;
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final AppleConfig appleConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final FocusedModuleTargetMatcher focusModules;
  private final boolean isMainProject;
  private final Optional<BuildTarget> workspaceTarget;
  ImmutableSet<BuildTarget> targetsInRequiredProjects;

  public ProjectGenerator(
      TargetGraph targetGraph,
      AppleDependenciesCache dependenciesCache,
      Set<BuildTarget> initialTargets,
      Cell cell,
      Path outputDirectory,
      String projectName,
      String buildFileName,
      Set<Option> options,
      Optional<BuildTarget> targetToBuildWithBuck,
      ImmutableList<String> buildWithBuckFlags,
      boolean isMainProject,
      Optional<BuildTarget> workspaceTarget,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      FocusedModuleTargetMatcher focusModules,
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      Function<? super TargetNode<?, ?>, BuildRuleResolver> buildRuleResolverForNode,
      BuckEventBus buckEventBus,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig) {
    this.targetGraph = targetGraph;
    this.dependenciesCache = dependenciesCache;
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectCell = cell;
    this.projectFilesystem = cell.getFilesystem();
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.buildFileName = buildFileName;
    this.options = ImmutableSet.copyOf(options);
    this.targetToBuildWithBuck = targetToBuildWithBuck;
    this.buildWithBuckFlags = buildWithBuckFlags;
    this.isMainProject = isMainProject;
    this.workspaceTarget = workspaceTarget;
    this.targetsInRequiredProjects = targetsInRequiredProjects;
    this.executableFinder = executableFinder;
    this.environment = environment;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.buildRuleResolverForNode = buildRuleResolverForNode;
    this.defaultBuildRuleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
    this.defaultPathResolver = new SourcePathResolver(
        new SourcePathRuleFinder(this.defaultBuildRuleResolver));
    this.buckEventBus = buckEventBus;

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.pathRelativizer = new PathRelativizer(
        outputDirectory,
        this::resolveSourcePath);

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.pathRelativizer.outputDirToRootRelative(Paths.get(".")));

    this.project = new PBXProject(projectName);
    this.headerSymlinkTrees = new ArrayList<>();

    this.targetNodeToGeneratedProjectTargetBuilder = ImmutableMultimap.builder();
    this.targetNodeToProjectTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<TargetNode<?, ?>, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(TargetNode<?, ?> key) throws Exception {
            return generateProjectTarget(key);
          }
        });

    targetConfigNamesBuilder = ImmutableSet.builder();
    gidsToTargetNames = new HashMap<>();
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.focusModules = focusModules;
  }

  @VisibleForTesting
  PBXProject getGeneratedProject() {
    return project;
  }

  @VisibleForTesting
  List<Path> getGeneratedHeaderSymlinkTrees() {
    return headerSymlinkTrees;
  }

  static PackagedResource getPackagedResourceNamed(ProjectFilesystem filesystem, String name) {
    return new PackagedResource(filesystem, ProjectGenerator.class, name);
  }

  static Path getPathToBuck(
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment) {
    return executableFinder.getExecutable(Paths.get("buck"), environment);
  }

  @VisibleForTesting
  static Path getBuildWithBuckPythonScriptPath(ProjectFilesystem filesystem) {
    return getPackagedResourceNamed(filesystem, BUILD_WITH_BUCK_PY_TEMPLATE).get();
  }

  @VisibleForTesting
  static Path getFixUUIDScriptPath(ProjectFilesystem filesystem) {
    return getPackagedResourceNamed(filesystem, FIX_UUID_PY_RESOURCE).get();
  }

  @VisibleForTesting
  static Path getCodesignScriptPath(ProjectFilesystem filesystem) {
    return getPackagedResourceNamed(filesystem, CODESIGN_PY_RESOURCE).get();
  }

  public Path getProjectPath() {
    return projectPath;
  }

  private boolean isHeaderMapDisabled() {
    return options.contains(Option.DISABLE_HEADER_MAPS);
  }

  private boolean shouldMergeHeaderMaps() {
    return options.contains(Option.MERGE_HEADER_MAPS) &&
        workspaceTarget.isPresent() &&
        !isHeaderMapDisabled();
  }

  private boolean shouldGenerateHeaderSymlinkTreesOnly() {
    return options.contains(Option.GENERATE_HEADERS_SYMLINK_TREES_ONLY);
  }

  public ImmutableMultimap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMap =
        ImmutableMultimap.builder();
    for (Map.Entry<TargetNode<?, ?>, PBXTarget> entry :
        targetNodeToGeneratedProjectTargetBuilder.build().entries()) {
      buildTargetToPbxTargetMap.put(entry.getKey().getBuildTarget(), entry.getValue());
    }
    return buildTargetToPbxTargetMap.build();
  }

  public ImmutableSet<BuildTarget> getRequiredBuildTargets() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    return requiredBuildTargetsBuilder.build();
  }

  // Returns true if we ran the project generation and we decided to eventually generate
  // the project.
  public boolean isProjectGenerated() {
    return projectGenerated;
  }

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    boolean hasAtLeastOneTarget = false;
    try (
        SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
            buckEventBus,
            PerfEventId.of("xcode_project_generation"),
            ImmutableMap.of("Path", getProjectPath()))) {
      for (TargetNode<?, ?> targetNode : targetGraph.getNodes()) {
        if (isBuiltByCurrentProject(targetNode.getBuildTarget())) {
          LOG.debug("Including rule %s in project", targetNode);
          // Trigger the loading cache to call the generateProjectTarget function.
          Optional<PBXTarget> target = targetNodeToProjectTarget.getUnchecked(targetNode);
          target.ifPresent(pbxTarget ->
              targetNodeToGeneratedProjectTargetBuilder.put(targetNode, pbxTarget));
          if (focusModules.isFocusedOn(targetNode.getBuildTarget())) {
            // If the target is not included, we still need to do other operations to generate
            // the required header maps.
            hasAtLeastOneTarget = true;
          }
        } else {
          LOG.verbose("Excluding rule %s (not built by current project)", targetNode);
        }
      }

      if (!hasAtLeastOneTarget && focusModules.hasFocus()) {
        return;
      }

      targetToBuildWithBuck.ifPresent(buildTarget ->
          generateBuildWithBuckTarget(targetGraph.get(buildTarget)));

      for (String configName : targetConfigNamesBuilder.build()) {
        XCBuildConfiguration outputConfig = project
            .getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configName);
        outputConfig.setBuildSettings(new NSDictionary());
      }

      if (!shouldGenerateHeaderSymlinkTreesOnly()) {
        writeProjectFile(project);
      }

      projectGenerated = true;
    } catch (UncheckedExecutionException e) {
      // if any code throws an exception, they tend to get wrapped in LoadingCache's
      // UncheckedExecutionException. Unwrap it if its cause is HumanReadable.
      UncheckedExecutionException originalException = e;
      while (e.getCause() instanceof UncheckedExecutionException) {
        e = (UncheckedExecutionException) e.getCause();
      }
      if (e.getCause() instanceof HumanReadableException) {
        throw (HumanReadableException) e.getCause();
      } else {
        throw originalException;
      }
    }
  }

  private void generateBuildWithBuckTarget(TargetNode<?, ?> targetNode) {
    final BuildTarget buildTarget = targetNode.getBuildTarget();

    String buckTargetProductName = getXcodeTargetName(buildTarget) + BUILD_WITH_BUCK_POSTFIX;

    PBXAggregateTarget buildWithBuckTarget = new PBXAggregateTarget(buckTargetProductName);
    buildWithBuckTarget.setProductName(buckTargetProductName);

    PBXShellScriptBuildPhase buildShellScriptBuildPhase = new PBXShellScriptBuildPhase();
    buildShellScriptBuildPhase.setShellScript(getBuildWithBuckShellScript(targetNode));
    buildWithBuckTarget.getBuildPhases().add(buildShellScriptBuildPhase);

    // Only add a shell script for fixing UUIDs if it is an AppleBundle
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      PBXShellScriptBuildPhase codesignPhase = new PBXShellScriptBuildPhase();
      codesignPhase.setShellScript(getCodesignShellScript(targetNode));
      buildWithBuckTarget.getBuildPhases().add(codesignPhase);
    }

    TargetNode<CxxLibraryDescription.Arg, ?> node =
        getAppleNativeNode(targetGraph, targetNode).get();
    ImmutableMap<String, ImmutableMap<String, String>> configs =
        getXcodeBuildConfigurationsForTargetNode(node, ImmutableMap.of()).get();

    XCConfigurationList configurationList = new XCConfigurationList();
    PBXGroup group = project
        .getMainGroup()
        .getOrCreateDescendantGroupByPath(
            RichStream.from(buildTarget.getBasePath())
                .map(Object::toString)
                .toImmutableList())
        .getOrCreateChildGroupByName(getXcodeTargetName(buildTarget));
    for (String configurationName : configs.keySet()) {
      XCBuildConfiguration configuration = configurationList
          .getBuildConfigurationsByName()
          .getUnchecked(configurationName);
      configuration.setBaseConfigurationReference(
          getConfigurationFileReference(
              group,
              getConfigurationXcconfigPath(buildTarget, configurationName)));

      NSDictionary inlineSettings = new NSDictionary();
      inlineSettings.put("HEADER_SEARCH_PATHS", "");
      inlineSettings.put("LIBRARY_SEARCH_PATHS", "");
      inlineSettings.put("FRAMEWORK_SEARCH_PATHS", "");
      configuration.setBuildSettings(inlineSettings);
    }

    buildWithBuckTarget.setBuildConfigurationList(configurationList);
    project.getTargets().add(buildWithBuckTarget);

    targetNodeToGeneratedProjectTargetBuilder.put(targetNode, buildWithBuckTarget);
  }

  private static Optional<String> getProductNameForTargetNode(TargetNode<?, ?> targetNode) {
    return targetNode.castArg(AppleBundleDescription.Arg.class)
        .flatMap(node -> node.getConstructorArg().productName);
  }

  private String getBuildFlags() {
    ArrayList<String> flags = new ArrayList<>(buildWithBuckFlags);
    if (!flags.contains(REPORT_ABSOLUTE_PATHS)) {
      flags.add(0, REPORT_ABSOLUTE_PATHS);
    }
    if (!flags.contains(SHOW_OUTPUT)) {
      flags.add(0, SHOW_OUTPUT);
    }
    return flags.stream()
        .map(Escaper.BASH_ESCAPER::apply)
        .collect(Collectors.joining(" "));
  }

  private String getBuildWithBuckShellScript(TargetNode<?, ?> targetNode) {
    ST template;
    try {
      template = new ST(Resources.toString(
          Resources.getResource(ProjectGenerator.class, BUILD_WITH_BUCK_TEMPLATE),
          Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "There was an error loading '" + BUILD_WITH_BUCK_TEMPLATE + "' template", e);
    }

    String buildFlags = getBuildFlags();
    String escapedBuildTarget = Escaper.escapeAsBashString(
        targetNode.getBuildTarget().getFullyQualifiedName());

    Optional<String> productName = getProductNameForTargetNode(targetNode);
    String binaryName = AppleBundle.getBinaryName(targetNode.getBuildTarget(), productName);
    Path bundleDestination =
        getScratchPathForAppBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path dsymDestination =
        getScratchPathForDsymBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path resolvedBundleDestination = projectFilesystem.resolve(bundleDestination);
    Path resolvedDsymDestination = projectFilesystem.resolve(dsymDestination);

    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(targetNode.getBuildTarget().getFlavors());
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(flavors).orElse(defaultCxxPlatform);
    String oldCompDir = cxxPlatform.getCompilerDebugPathSanitizer().getCompilationDirectory();
    // Use the hostname for padding instead of the directory, this way the directory matches without
    // having to resolve it.
    String dsymPaddedCompDirWithHost = Strings.padStart(
        ":" + projectFilesystem.getRootPath().toString(),
        oldCompDir.length(),
        'f');


    template.add("path_to_build_with_buck_py", getBuildWithBuckPythonScriptPath(projectFilesystem));
    template.add("path_to_fix_uuid_script", getFixUUIDScriptPath(projectFilesystem));
    template.add("repo_root", projectFilesystem.getRootPath());
    template.add("path_to_buck", getPathToBuck(executableFinder, environment));
    template.add("build_flags", buildFlags);
    template.add("escaped_build_target", escapedBuildTarget);
    template.add(
        "buck_dwarf_flavor",
        (appleConfig.forceDsymModeInBuildWithBuck() ?
            AppleDebugFormat.DWARF_AND_DSYM :
            AppleDebugFormat.DWARF)
            .getFlavor().getName());
    template.add("buck_dsym_flavor", AppleDebugFormat.DWARF_AND_DSYM.getFlavor().getName());
    template.add("binary_name", binaryName);
    template.add("comp_dir", oldCompDir);
    template.add("new_comp_dir", projectFilesystem.getRootPath().toString());
    template.add("padded_source_dir", dsymPaddedCompDirWithHost);
    template.add("resolved_bundle_destination", resolvedBundleDestination);
    template.add("resolved_bundle_destination_parent", resolvedBundleDestination.getParent());
    template.add("resolved_dsym_destination", resolvedDsymDestination);
    template.add("force_dsym", appleConfig.forceDsymModeInBuildWithBuck() ? "true" : "false");

    return template.render();
  }

  private String getCodesignShellScript(TargetNode<?, ?> targetNode) {
    ST template;
    try {
      template = new ST(Resources.toString(
          Resources.getResource(ProjectGenerator.class, CODESIGN_TEMPLATE), Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "There was an error loading '" + CODESIGN_TEMPLATE + "' template", e);
    }

    Optional<String> productName = getProductNameForTargetNode(targetNode);
    String binaryName = AppleBundle.getBinaryName(targetNode.getBuildTarget(), productName);
    Path bundleDestination =
        getScratchPathForAppBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path resolvedBundleDestination = projectFilesystem.resolve(bundleDestination);

    template.add("root_path", projectFilesystem.getRootPath());
    template.add("path_to_codesign_script", getCodesignScriptPath(projectFilesystem));
    template.add("app_bundle_path", resolvedBundleDestination);

    return template.render();
  }

  static Path getScratchPathForAppBundle(
      ProjectFilesystem filesystem,
      BuildTarget targetToBuildWithBuck,
      String binaryName) {
    return BuildTargets
        .getScratchPath(filesystem, targetToBuildWithBuck, "%s-unsanitised")
        .resolve(binaryName + ".app");
  }

  static Path getScratchPathForDsymBundle(
      ProjectFilesystem filesystem,
      BuildTarget targetToBuildWithBuck,
      String binaryName) {
    return BuildTargets
        .getScratchPath(filesystem, targetToBuildWithBuck, "%s-unsanitised")
        .resolve(binaryName + ".dSYM");
  }

  @SuppressWarnings("unchecked")
  private Optional<PBXTarget> generateProjectTarget(TargetNode<?, ?> targetNode)
      throws IOException {
    Preconditions.checkState(
        isBuiltByCurrentProject(targetNode.getBuildTarget()),
        "should not generate rule if it shouldn't be built by current project");
    Optional<PBXTarget> result = Optional.empty();
    if (targetNode.getDescription() instanceof AppleLibraryDescription) {
      result = Optional.of(
          generateAppleLibraryTarget(
              project,
              (TargetNode<AppleNativeTargetDescriptionArg, ?>) targetNode,
              Optional.empty()));
    } else if (
        targetNode.getDescription() instanceof CxxLibraryDescription) {
      result = Optional.of(
          generateCxxLibraryTarget(
              project,
              (TargetNode<CxxLibraryDescription.Arg, ?>) targetNode,
              ImmutableSet.of(),
              ImmutableSet.of(),
              Optional.empty()));
    } else if (
        targetNode.getDescription() instanceof AppleBinaryDescription) {
      result = Optional.of(
          generateAppleBinaryTarget(
              project,
              (TargetNode<AppleNativeTargetDescriptionArg, ?>) targetNode));
    } else if (
        targetNode.getDescription() instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescription.Arg, ?> bundleTargetNode =
          (TargetNode<AppleBundleDescription.Arg, ?>) targetNode;
      result = Optional.of(
          generateAppleBundleTarget(
              project,
              bundleTargetNode,
              (TargetNode<AppleNativeTargetDescriptionArg, ?>)
                  targetGraph.get(bundleTargetNode.getConstructorArg().binary),
              Optional.empty()));
    } else if (
        targetNode.getDescription() instanceof AppleTestDescription) {
      result = Optional.of(
          generateAppleTestTarget((TargetNode<AppleTestDescription.Arg, ?>) targetNode));
    } else if (
        targetNode.getDescription() instanceof AppleResourceDescription) {
      checkAppleResourceTargetNodeReferencingValidContents(
          (TargetNode<AppleResourceDescription.Arg, ?>) targetNode);
    } else if (
        targetNode.getDescription() instanceof HalideLibraryDescription) {
      TargetNode<HalideLibraryDescription.Arg, ?> halideTargetNode =
          (TargetNode<HalideLibraryDescription.Arg, ?>) targetNode;
      BuildTarget buildTarget = targetNode.getBuildTarget();

      // The generated target just runs a shell script that invokes the "compiler" with the
      // correct target architecture.
      result = generateHalideLibraryTarget(project, halideTargetNode);

      // Make sure the compiler gets built at project time, since we'll need
      // it to generate the shader code during the Xcode build.
      requiredBuildTargetsBuilder.add(
          HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget));

      // HACK: Don't generate the Halide headers unless the compiler is expected
      // to generate output for the default platform -- a Halide library that
      // uses a platform regex may not be able to use the default platform.
      // This assumes that there's a 'default' variant of the rule to generate
      // headers from.
      if (HalideLibraryDescription.isPlatformSupported(
          halideTargetNode.getConstructorArg(),
          defaultCxxPlatform)) {

        // Run the compiler once at project time to generate the header
        // file needed for compilation if the Halide target is for the default
        // platform.
        requiredBuildTargetsBuilder.add(
            buildTarget.withFlavors(
                HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
                defaultCxxPlatform.getFlavor()));
      }
    }
    buckEventBus.post(ProjectGenerationEvent.processed());
    return result;
  }

  private static Path getHalideOutputPath(ProjectFilesystem filesystem, BuildTarget target) {
    return filesystem.getBuckPaths().getBuckOut()
        .resolve("halide")
        .resolve(target.getBasePath())
        .resolve(target.getShortName());
  }

  private Optional<PBXTarget> generateHalideLibraryTarget(
      PBXProject project,
      TargetNode<HalideLibraryDescription.Arg, ?> targetNode) throws IOException {
    final BuildTarget buildTarget = targetNode.getBuildTarget();
    boolean isFocusedOnTarget = focusModules.isFocusedOn(buildTarget);
    String productName = getProductNameForBuildTarget(buildTarget);
    Path outputPath = getHalideOutputPath(targetNode.getFilesystem(), buildTarget);

    Path scriptPath = halideBuckConfig.getXcodeCompileScriptPath();
    Optional<String> script = projectFilesystem.readFileIfItExists(scriptPath);
    PBXShellScriptBuildPhase scriptPhase = new PBXShellScriptBuildPhase();
    scriptPhase.setShellScript(script.orElse(""));

    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        this::resolveSourcePath);
    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(ProductType.STATIC_LIBRARY, productName, outputPath)
        .setPreBuildRunScriptPhases(ImmutableList.of(scriptPhase));

    NewNativeTargetProjectMutator.Result targetBuilderResult;
    targetBuilderResult = mutator.buildTargetAndAddToProject(project, isFocusedOnTarget);

    BuildTarget compilerTarget =
        HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget);
    Path compilerPath = BuildTargets.getGenPath(projectFilesystem, compilerTarget, "%s");
    ImmutableMap<String, String> appendedConfig = ImmutableMap.of();
    ImmutableMap<String, String> extraSettings = ImmutableMap.of();
    ImmutableMap.Builder<String, String> defaultSettingsBuilder =
        ImmutableMap.builder();
    defaultSettingsBuilder.put(
        "REPO_ROOT",
        projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
    defaultSettingsBuilder.put("HALIDE_COMPILER_PATH", compilerPath.toString());

    // pass the source list to the xcode script
    String halideCompilerSrcs;
    Iterable<Path> compilerSrcFiles =
        Iterables.transform(
            targetNode.getConstructorArg().srcs,
            input -> resolveSourcePath(input.getSourcePath())
        );
    halideCompilerSrcs = Joiner.on(" ").join(compilerSrcFiles);
    defaultSettingsBuilder.put("HALIDE_COMPILER_SRCS", halideCompilerSrcs);
    String halideCompilerFlags;
    halideCompilerFlags = Joiner.on(" ").join(targetNode.getConstructorArg().compilerFlags);
    defaultSettingsBuilder.put("HALIDE_COMPILER_FLAGS", halideCompilerFlags);

    defaultSettingsBuilder.put("HALIDE_OUTPUT_PATH", outputPath.toString());
    defaultSettingsBuilder.put("HALIDE_FUNC_NAME", buildTarget.getShortName());
    defaultSettingsBuilder.put(PRODUCT_NAME, productName);

    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
        getXcodeBuildConfigurationsForTargetNode(
            targetNode,
            appendedConfig);
    PBXNativeTarget target = targetBuilderResult.target;
    setTargetBuildConfigurations(
        buildTarget,
        target,
        project.getMainGroup(),
        configs.get(),
        extraSettings,
        defaultSettingsBuilder.build(),
        appendedConfig);
    return Optional.of(target);
  }

  private PBXTarget generateAppleTestTarget(
      TargetNode<AppleTestDescription.Arg, ?> testTargetNode) throws IOException {
    Optional<TargetNode<AppleBundleDescription.Arg, ?>> testHostBundle =
        testTargetNode.getConstructorArg().testHostApp.map(testHostBundleTarget -> {
          TargetNode<?, ?> testHostBundleNode = targetGraph.get(testHostBundleTarget);
          return testHostBundleNode.castArg(AppleBundleDescription.Arg.class)
              .orElseGet(() -> {
                throw new HumanReadableException(
                    "The test host target '%s' has the wrong type (%s), must be apple_bundle",
                    testHostBundleTarget,
                    testHostBundleNode.getDescription().getClass());
              });
        });
    return generateAppleBundleTarget(
        project,
        testTargetNode,
        testTargetNode,
        testHostBundle);
  }

  private void checkAppleResourceTargetNodeReferencingValidContents(
      TargetNode<AppleResourceDescription.Arg, ?> resource) {
    // Check that the resource target node is referencing valid files or directories.
    // If a SourcePath is a BuildTargetSourcePath (or some hypothetical future implementation of
    // AbstractSourcePath), just assume it's the right type; we have no way of checking now as it
    // may not exist yet.
    AppleResourceDescription.Arg arg = resource.getConstructorArg();
    for (SourcePath dir : arg.dirs) {
      if (dir instanceof PathSourcePath &&
          !projectFilesystem.isDirectory(resolveSourcePath(dir))) {
        throw new HumanReadableException(
            "%s specified in the dirs parameter of %s is not a directory",
            dir.toString(), resource.toString());
      }
    }
    for (SourcePath file : arg.files) {
      if (file instanceof PathSourcePath &&
          !projectFilesystem.isFile(resolveSourcePath(file))) {
        throw new HumanReadableException(
            "%s specified in the files parameter of %s is not a regular file",
            file.toString(), resource.toString());
      }
    }
  }

  private PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      TargetNode<? extends HasAppleBundleFields, ?> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg, ?> binaryNode,
      Optional<TargetNode<AppleBundleDescription.Arg, ?>> bundleLoaderNode)
      throws IOException {
    Path infoPlistPath =
        Preconditions.checkNotNull(
            resolveSourcePath(targetNode.getConstructorArg().getInfoPlist()));

    // -- copy any binary and bundle targets into this bundle
    Iterable<TargetNode<?, ?>> copiedRules =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.COPYING,
            targetNode,
            Optional.of(AppleBuildRules.XCODE_TARGET_DESCRIPTION_CLASSES));
    if (bundleRequiresRemovalOfAllTransitiveFrameworks(targetNode)) {
      copiedRules = rulesWithoutFrameworkBundles(copiedRules);
    } else if (bundleRequiresAllTransitiveFrameworks(binaryNode)) {
      copiedRules = ImmutableSet.<TargetNode<?, ?>>builder()
          .addAll(copiedRules)
          .addAll(getTransitiveFrameworkNodes(targetNode))
          .build();
    }

    if (bundleLoaderNode.isPresent()) {
      copiedRules = rulesWithoutBundleLoader(copiedRules, bundleLoaderNode.get());
    }

    ImmutableList<PBXBuildPhase> copyFilesBuildPhases = getCopyFilesBuildPhases(copiedRules);

    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.of(targetNode),
        binaryNode,
        bundleToTargetProductType(targetNode, binaryNode),
        "%s." + getExtensionString(targetNode.getConstructorArg().getExtension()),
        Optional.of(infoPlistPath),
        /* includeFrameworks */ true,
        AppleResources.collectRecursiveResources(
            targetGraph,
            Optional.of(dependenciesCache),
            ImmutableList.of(targetNode)),
        AppleResources.collectDirectResources(targetGraph, targetNode),
        AppleBuildRules.collectRecursiveAssetCatalogs(
            targetGraph,
            Optional.of(dependenciesCache),
            ImmutableList.of(targetNode)),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        AppleBuildRules.collectRecursiveWrapperResources(
            targetGraph,
            Optional.of(dependenciesCache),
            ImmutableList.of(targetNode)),
        Optional.of(copyFilesBuildPhases), bundleLoaderNode);

    LOG.debug("Generated iOS bundle target %s", target);
    return target;
  }

  /**
   * Traverses the graph to find all (non-system) frameworks that should be embedded into the
   * target's bundle.
   */
  private ImmutableSet<TargetNode<?, ?>> getTransitiveFrameworkNodes(
      TargetNode<? extends HasAppleBundleFields, ?> targetNode) {
    GraphTraversable<TargetNode<?, ?>> graphTraversable = node -> {
      if (!(node.getDescription() instanceof AppleResourceDescription)) {
        return targetGraph.getAll(node.getBuildDeps()).iterator();
      } else {
        return Collections.emptyIterator();
      }
    };

    final ImmutableSet.Builder<TargetNode<?, ?>> filteredRules = ImmutableSet.builder();
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?, ?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable);
    try {
      for (TargetNode<?, ?> node : traversal.traverse(ImmutableList.of(targetNode))) {
        if (node != targetNode) {
          node.castArg(AppleBundleDescription.Arg.class).ifPresent(appleBundleNode ->  {
            if (isFrameworkBundle(appleBundleNode.getConstructorArg())) {
              filteredRules.add(node);
            }
          });
          node.castArg(PrebuiltAppleFrameworkDescription.Arg.class).ifPresent(prebuiltFramework -> {
            // Technically (see Apple Tech Notes 2435), static frameworks are lies. In case a static
            // framework is used, they can escape the incorrect project generation by marking its
            // preferred linkage static (what does preferred linkage even mean for a prebuilt thing?
            // none of this makes sense anyways).
            if (prebuiltFramework.getConstructorArg().preferredLinkage !=
                NativeLinkable.Linkage.STATIC) {
              filteredRules.add(node);
            }
          });
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new RuntimeException(e);
    }
    return filteredRules.build();
  }

  /**
   * Returns a new list of rules which does not contain framework bundles.
   */
  private ImmutableList<TargetNode<?, ?>> rulesWithoutFrameworkBundles(
      Iterable<TargetNode<?, ?>> copiedRules) {
    return RichStream.from(copiedRules)
        .filter(input ->
            input.castArg(AppleBundleDescription.Arg.class)
                .map(argTargetNode -> !isFrameworkBundle(argTargetNode.getConstructorArg()))
                .orElse(true))
        .toImmutableList();
  }

  private ImmutableList<TargetNode<?, ?>> rulesWithoutBundleLoader(
      Iterable<TargetNode<?, ?>> copiedRules,
      TargetNode<?, ?> bundleLoader) {
    return RichStream.from(copiedRules)
        .filter(x -> !bundleLoader.equals(x))
        .toImmutableList();
  }

  private PBXNativeTarget generateAppleBinaryTarget(
      PBXProject project,
      TargetNode<AppleNativeTargetDescriptionArg, ?> targetNode)
      throws IOException {
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.empty(),
        targetNode,
        ProductType.TOOL,
        "%s",
        Optional.empty(),
        /* includeFrameworks */ true,
        ImmutableSet.of(),
        AppleResources.collectDirectResources(targetGraph, targetNode),
        ImmutableSet.of(),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        ImmutableSet.of(),
        Optional.empty(),
        Optional.empty());
    LOG.debug("Generated Apple binary target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      TargetNode<? extends AppleNativeTargetDescriptionArg, ?> targetNode,
      Optional<TargetNode<AppleBundleDescription.Arg, ?>> bundleLoaderNode)
      throws IOException {
    PBXNativeTarget target = generateCxxLibraryTarget(
        project,
        targetNode,
        AppleResources.collectDirectResources(targetGraph, targetNode),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        bundleLoaderNode);
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private PBXNativeTarget generateCxxLibraryTarget(
      PBXProject project,
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      ImmutableSet<AppleResourceDescription.Arg> directResources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> directAssetCatalogs,
      Optional<TargetNode<AppleBundleDescription.Arg, ?>> bundleLoaderNode)
      throws IOException {
    boolean isShared = targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProductType productType = isShared ?
        ProductType.DYNAMIC_LIBRARY :
        ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.empty(),
        targetNode,
        productType,
        AppleBuildRules.getOutputFileNameFormatForLibrary(isShared),
        Optional.empty(),
        /* includeFrameworks */ isShared,
        ImmutableSet.of(),
        directResources,
        ImmutableSet.of(),
        directAssetCatalogs,
        ImmutableSet.of(),
        Optional.empty(),
        bundleLoaderNode);
    LOG.debug("Generated Cxx library target %s", target);
    return target;
  }

  private ImmutableList<String> convertStringWithMacros(
      TargetNode<?, ?> node,
      Iterable<StringWithMacros> flags) {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    ImmutableList<? extends AbstractMacroExpander<? extends Macro>> expanders =
        ImmutableList.of(new AsIsLocationMacroExpander());
    for (StringWithMacros flag : flags) {
      StringWithMacrosArg
          .of(
              flag,
              expanders,
              node.getBuildTarget(),
              node.getCellNames(),
              defaultBuildRuleResolver)
          .appendToCommandLine(result, defaultPathResolver);
    }
    return result.build();
  }

  private PBXNativeTarget generateBinaryTarget(
      PBXProject project,
      Optional<? extends TargetNode<? extends HasAppleBundleFields, ?>> bundle,
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescription.Arg> recursiveResources,
      ImmutableSet<AppleResourceDescription.Arg> directResources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> recursiveAssetCatalogs,
      ImmutableSet<AppleAssetCatalogDescription.Arg> directAssetCatalogs,
      ImmutableSet<AppleWrapperResourceArg> wrapperResources,
      Optional<Iterable<PBXBuildPhase>> copyFilesPhases,
      Optional<TargetNode<AppleBundleDescription.Arg, ?>> bundleLoaderNode)
      throws IOException {

    LOG.debug("Generating binary target for node %s", targetNode);

    TargetNode<?, ?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;
    final BuildTarget buildTarget = buildTargetNode.getBuildTarget();

    String buildTargetName = getProductNameForBuildTarget(buildTarget);
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        this::resolveSourcePath);
    ImmutableSet<SourcePath> exportedHeaders =
        ImmutableSet.copyOf(getHeaderSourcePaths(arg.exportedHeaders));
    ImmutableSet<SourcePath> headers = ImmutableSet.copyOf(getHeaderSourcePaths(arg.headers));
    ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
        targetNode.getConstructorArg().langPreprocessorFlags;

    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(
            productType,
            buildTargetName,
            Paths.get(String.format(productOutputFormat, buildTargetName)));

    boolean isFocusedOnTarget = focusModules.isFocusedOn(buildTarget);
    Optional<TargetNode<AppleNativeTargetDescriptionArg, ?>> appleTargetNode =
        targetNode.castArg(AppleNativeTargetDescriptionArg.class);

    if (!shouldGenerateHeaderSymlinkTreesOnly()) {
      if (isFocusedOnTarget) {
        mutator
            .setLangPreprocessorFlags(langPreprocessorFlags)
            .setPublicHeaders(exportedHeaders)
            .setPrefixHeader(arg.prefixHeader)
            .setSourcesWithFlags(ImmutableSet.copyOf(arg.srcs))
            .setPrivateHeaders(headers)
            .setRecursiveResources(recursiveResources)
            .setDirectResources(directResources)
            .setWrapperResources(wrapperResources);
      }

      if (bundle.isPresent() && isFocusedOnTarget) {
        HasAppleBundleFields bundleArg = bundle.get().getConstructorArg();
        mutator.setInfoPlist(Optional.of(bundleArg.getInfoPlist()));
      }

      mutator.setBridgingHeader(arg.bridgingHeader);

      if (appleTargetNode.isPresent() && isFocusedOnTarget) {
        AppleNativeTargetDescriptionArg appleArg = appleTargetNode.get().getConstructorArg();
        mutator = mutator
            .setExtraXcodeSources(ImmutableSet.copyOf(appleArg.extraXcodeSources));
      }

      if (options.contains(Option.CREATE_DIRECTORY_STRUCTURE) && isFocusedOnTarget) {
        mutator.setTargetGroupPath(
            RichStream.from(buildTarget.getBasePath()).map(Object::toString).toImmutableList());
      }

      if (!recursiveAssetCatalogs.isEmpty() && isFocusedOnTarget) {
        mutator.setRecursiveAssetCatalogs(recursiveAssetCatalogs);
      }

      if (!directAssetCatalogs.isEmpty() && isFocusedOnTarget) {
        mutator.setDirectAssetCatalogs(directAssetCatalogs);
      }

      if (includeFrameworks && isFocusedOnTarget) {
        ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
        frameworksBuilder.addAll(targetNode.getConstructorArg().getFrameworks());
        frameworksBuilder.addAll(targetNode.getConstructorArg().getLibraries());
        frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(targetNode));
        mutator.setFrameworks(frameworksBuilder.build());

        mutator.setArchives(collectRecursiveLibraryDependencies(targetNode));
      }

      // TODO(Task #3772930): Go through all dependencies of the rule
      // and add any shell script rules here
      ImmutableList.Builder<TargetNode<?, ?>> preScriptPhases = ImmutableList.builder();
      ImmutableList.Builder<TargetNode<?, ?>> postScriptPhases = ImmutableList.builder();
      if (bundle.isPresent() && targetNode != bundle.get() && isFocusedOnTarget) {
        collectBuildScriptDependencies(
            targetGraph.getAll(bundle.get().getDeclaredDeps()),
            preScriptPhases,
            postScriptPhases);
      }
      collectBuildScriptDependencies(
          targetGraph.getAll(targetNode.getDeclaredDeps()),
          preScriptPhases,
          postScriptPhases);
      if (isFocusedOnTarget) {
        mutator.setPreBuildRunScriptPhasesFromTargetNodes(preScriptPhases.build());
        if (copyFilesPhases.isPresent()) {
          mutator.setCopyFilesPhases(copyFilesPhases.get());
        }
        mutator.setPostBuildRunScriptPhasesFromTargetNodes(postScriptPhases.build());
      }
    }

    NewNativeTargetProjectMutator.Result targetBuilderResult =
        mutator.buildTargetAndAddToProject(project, isFocusedOnTarget);
    PBXNativeTarget target = targetBuilderResult.target;
    Optional<PBXGroup> targetGroup = targetBuilderResult.targetGroup;

    ImmutableMap.Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();

    if (!shouldGenerateHeaderSymlinkTreesOnly()) {
      if (isFocusedOnTarget) {
        SourceTreePath buckFilePath = new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToBuildTargetPath(buildTarget).resolve(buildFileName),
            Optional.empty());
        PBXFileReference buckReference =
            targetGroup.get().getOrCreateFileReferenceBySourceTreePath(buckFilePath);
        buckReference.setExplicitFileType(Optional.of("text.script.python"));
      }

      // -- configurations
      extraSettingsBuilder
          .put("TARGET_NAME", buildTargetName)
          .put("SRCROOT", pathRelativizer.outputPathToBuildTargetPath(buildTarget).toString());
      if (productType == ProductType.UI_TEST && isFocusedOnTarget) {
        if (bundleLoaderNode.isPresent()) {
          BuildTarget testTarget = bundleLoaderNode.get().getBuildTarget();
          extraSettingsBuilder
              .put("TEST_TARGET_NAME", getXcodeTargetName(testTarget));
        } else {
          throw new HumanReadableException(
              "The test rule '%s' is configured with 'is_ui_test' but has no test_host_app",
              buildTargetName);
        }
      } else if (bundleLoaderNode.isPresent() && isFocusedOnTarget) {
        TargetNode<AppleBundleDescription.Arg, ?> bundleLoader = bundleLoaderNode.get();
        String bundleLoaderProductName =
            getProductNameForBuildTarget(bundleLoader.getBuildTarget());
        String bundleLoaderBundleName = bundleLoaderProductName + "." +
            getExtensionString(bundleLoader.getConstructorArg().getExtension());
        // NOTE(grp): This is a hack. We need to support both deep (OS X) and flat (iOS)
        // style bundles for the bundle loader, but at this point we don't know what platform
        // the bundle loader (or current target) is going to be built for. However, we can be
        // sure that it's the same as the target (presumably a test) we're building right now.
        //
        // Using that knowledge, we can do build setting tricks to defer choosing the bundle
        // loader path until Xcode build time, when the platform is known. There's no build
        // setting that conclusively says whether the current platform uses deep bundles:
        // that would be too easy. But in the cases we care about (unit test bundles), the
        // current bundle will have a style matching the style of the bundle loader app, so
        // we can take advantage of that to do the determination.
        //
        // Unfortunately, the build setting for the bundle structure (CONTENTS_FOLDER_PATH)
        // includes the WRAPPER_NAME, so we can't just interpolate that in. Instead, we have
        // to use another trick with build setting operations and evaluation. By using the
        // $(:file) operation, we can extract the last component of the contents path: either
        // "Contents" or the current bundle name. Then, we can interpolate with that expected
        // result in the build setting name to conditionally choose a different loader path.

        // The conditional that decides which path is used. This is a complex Xcode build setting
        // expression that expands to one of two values, depending on the last path component of
        // the CONTENTS_FOLDER_PATH variable. As described above, this will be either "Contents"
        // for deep bundles or the bundle file name itself for flat bundles. Finally, to santiize
        // the potentially invalid build setting names from the bundle file name, it converts that
        // to an identifier. We rely on BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_<bundle file name>
        // being undefined (and thus expanding to nothing) for the path resolution to work.
        //
        // The operations on the CONTENTS_FOLDER_PATH are documented here:
        // http://codeworkshop.net/posts/xcode-build-setting-transformations
        String bundleLoaderOutputPathConditional =
            "$(BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_$(CONTENTS_FOLDER_PATH:file:identifier))";

        // If the $(CONTENTS_FOLDER_PATH:file:identifier) expands to this, we add the deep bundle
        // path into the bundle loader. See above for the case when it will expand to this value.
        String bundleLoaderOutputPathDeepSetting =
            "BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_Contents";
        String bundleLoaderOutputPathDeepValue = "Contents/MacOS/";

        String bundleLoaderOutputPathValue = Joiner.on('/').join(
            getTargetOutputPath(bundleLoader),
            bundleLoaderBundleName,
            bundleLoaderOutputPathConditional,
            bundleLoaderProductName);

        extraSettingsBuilder
            .put(bundleLoaderOutputPathDeepSetting, bundleLoaderOutputPathDeepValue)
            .put("BUNDLE_LOADER", bundleLoaderOutputPathValue)
            .put("TEST_HOST", "$(BUNDLE_LOADER)");
      }
      if (infoPlistOptional.isPresent()) {
        Path infoPlistPath = pathRelativizer.outputDirToRootRelative(infoPlistOptional.get());
        extraSettingsBuilder.put("INFOPLIST_FILE", infoPlistPath.toString());
      }
      if (arg.bridgingHeader.isPresent()) {
        Path bridgingHeaderPath = pathRelativizer.outputDirToRootRelative(
            resolveSourcePath(arg.bridgingHeader.get()));
        extraSettingsBuilder.put(
            "SWIFT_OBJC_BRIDGING_HEADER",
            Joiner.on('/').join("$(SRCROOT)", bridgingHeaderPath.toString()));
      }
      Optional<String> swiftVersion = swiftBuckConfig.getVersion();
      swiftVersion.ifPresent(s -> extraSettingsBuilder.put("SWIFT_VERSION", s));
      Optional<SourcePath> prefixHeaderOptional = targetNode.getConstructorArg().prefixHeader;
      if (prefixHeaderOptional.isPresent()) {
        Path prefixHeaderRelative = resolveSourcePath(prefixHeaderOptional.get());
        Path prefixHeaderPath = pathRelativizer.outputDirToRootRelative(prefixHeaderRelative);
        extraSettingsBuilder.put("GCC_PREFIX_HEADER", prefixHeaderPath.toString());
        extraSettingsBuilder.put("GCC_PRECOMPILE_PREFIX_HEADER", "YES");
      }
      extraSettingsBuilder.put("USE_HEADERMAP", "NO");

      defaultSettingsBuilder.put(
          "REPO_ROOT",
          projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
      defaultSettingsBuilder.put(PRODUCT_NAME, getProductName(buildTargetNode, buildTarget));
      bundle.ifPresent(bundleNode ->
          defaultSettingsBuilder.put(
              "WRAPPER_EXTENSION",
              getExtensionString(bundleNode.getConstructorArg().getExtension())));

      // We use BUILT_PRODUCTS_DIR as the root for the everything being built. Target-
      // specific output is placed within CONFIGURATION_BUILD_DIR, inside BUILT_PRODUCTS_DIR.
      // That allows Copy Files build phases to reference files in the CONFIGURATION_BUILD_DIR
      // of other targets by using paths relative to the target-independent BUILT_PRODUCTS_DIR.
      defaultSettingsBuilder.put(
          "BUILT_PRODUCTS_DIR",
          // $EFFECTIVE_PLATFORM_NAME starts with a dash, so this expands to something like:
          // $SYMROOT/Debug-iphonesimulator
          Joiner.on('/').join("$SYMROOT", "$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"));
      defaultSettingsBuilder.put("CONFIGURATION_BUILD_DIR", "$BUILT_PRODUCTS_DIR");
      boolean nodeIsAppleLibrary =
          targetNode.getDescription() instanceof AppleLibraryDescription;
      boolean nodeIsCxxLibrary =
          targetNode.getDescription() instanceof CxxLibraryDescription;
      if (!bundle.isPresent() && (nodeIsAppleLibrary || nodeIsCxxLibrary)) {
        defaultSettingsBuilder.put("EXECUTABLE_PREFIX", "lib");
      }

      if (isFocusedOnTarget) {
        ImmutableSet<Path> recursiveHeaderSearchPaths =
            collectRecursiveHeaderSearchPaths(targetNode);
        ImmutableSet<Path> headerMapBases = recursiveHeaderSearchPaths.isEmpty() ?
            ImmutableSet.of() :
            ImmutableSet.of(
                pathRelativizer.outputDirToRootRelative(
                    buildTargetNode.getFilesystem().getBuckPaths().getBuckOut()));

        ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();
        appendConfigsBuilder.putAll(getFrameworkAndLibrarySearchPathConfigs(targetNode));
        appendConfigsBuilder.put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(Iterables.concat(recursiveHeaderSearchPaths, headerMapBases)));

        Iterable<String> otherCFlags = Iterables.concat(
            cxxBuckConfig.getFlags("cflags").orElse(DEFAULT_CFLAGS),
            collectRecursiveExportedPreprocessorFlags(targetNode),
            targetNode.getConstructorArg().compilerFlags,
            targetNode.getConstructorArg().preprocessorFlags);
        Iterable<String> otherCxxFlags = Iterables.concat(
            cxxBuckConfig.getFlags("cxxflags").orElse(DEFAULT_CXXFLAGS),
            collectRecursiveExportedPreprocessorFlags(targetNode),
            targetNode.getConstructorArg().compilerFlags,
            targetNode.getConstructorArg().preprocessorFlags);
        ImmutableList<String> otherLdFlags = convertStringWithMacros(
            targetNode,
            Iterables.concat(
                targetNode.getConstructorArg().linkerFlags,
                collectRecursiveExportedLinkerFlags(targetNode)));

        appendConfigsBuilder
            .put(
                "OTHER_CFLAGS",
                Joiner.on(' ').join(Iterables.transform(otherCFlags, Escaper.BASH_ESCAPER)))
            .put(
                "OTHER_CPLUSPLUSFLAGS",
                Joiner.on(' ').join(Iterables.transform(otherCxxFlags, Escaper.BASH_ESCAPER)))
            .put(
                "OTHER_LDFLAGS",
                Joiner.on(' ').join(Iterables.transform(otherLdFlags, Escaper.BASH_ESCAPER)));

        ImmutableMultimap.Builder<String, ImmutableList<String>> platformFlagsBuilder =
            ImmutableMultimap.builder();
        for (Pair<Pattern, ImmutableList<String>> flags :
               Iterables.concat(
                   targetNode.getConstructorArg().platformCompilerFlags
                       .getPatternsAndValues(),
                   targetNode.getConstructorArg().platformPreprocessorFlags
                       .getPatternsAndValues(),
                   collectRecursiveExportedPlatformPreprocessorFlags(targetNode))) {
          String sdk = flags.getFirst().pattern().replaceAll("[*.]", "");
          platformFlagsBuilder.put(sdk, flags.getSecond());
        }
        ImmutableMultimap<String, ImmutableList<String>> platformFlags =
            platformFlagsBuilder.build();
        for (String sdk : platformFlags.keySet()) {
          appendConfigsBuilder
              .put(
                  String.format("OTHER_CFLAGS[sdk=*%s*]", sdk),
                  Joiner.on(' ').join(
                      Iterables.transform(
                          Iterables.concat(otherCFlags, Iterables.concat(platformFlags.get(sdk))),
                          Escaper.BASH_ESCAPER)))
              .put(
                  String.format("OTHER_CPLUSPLUSFLAGS[sdk=*%s*]", sdk),
                  Joiner.on(' ').join(
                      Iterables.transform(
                          Iterables.concat(otherCxxFlags, Iterables.concat(platformFlags.get(sdk))),
                          Escaper.BASH_ESCAPER)));
        }

        ImmutableMultimap.Builder<String, ImmutableList<String>> platformLinkerFlagsBuilder =
            ImmutableMultimap.builder();
        for (Pair<Pattern, ImmutableList<StringWithMacros>> flags :
            Iterables.concat(
                targetNode.getConstructorArg().platformLinkerFlags.getPatternsAndValues(),
                collectRecursiveExportedPlatformLinkerFlags(targetNode))) {
          String sdk = flags.getFirst().pattern().replaceAll("[*.]", "");
          platformLinkerFlagsBuilder.put(
              sdk,
              convertStringWithMacros(targetNode, flags.getSecond()));
        }
        ImmutableMultimap<String, ImmutableList<String>> platformLinkerFlags =
            platformLinkerFlagsBuilder.build();
        for (String sdk : platformLinkerFlags.keySet()) {
          appendConfigsBuilder
              .put(
                  String.format("OTHER_LDFLAGS[sdk=*%s*]", sdk),
                  Joiner.on(' ').join(
                      Iterables.transform(
                          Iterables.concat(
                              otherLdFlags,
                              Iterables.concat(platformLinkerFlags.get(sdk))),
                          Escaper.BASH_ESCAPER)));
        }

        ImmutableMap<String, String> appendedConfig = appendConfigsBuilder.build();

        Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
            getXcodeBuildConfigurationsForTargetNode(
                targetNode,
                appendedConfig);
        setTargetBuildConfigurations(
            buildTarget,
            target,
            project.getMainGroup(),
            configs.get(),
            extraSettingsBuilder.build(),
            defaultSettingsBuilder.build(),
            appendedConfig);
      }
    }

    // -- phases
    createHeaderSymlinkTree(
        getPublicCxxHeaders(targetNode),
        getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PUBLIC),
        arg.xcodePublicHeadersSymlinks.orElse(true) || isHeaderMapDisabled(),
        !shouldMergeHeaderMaps());
    if (isFocusedOnTarget) {
      createHeaderSymlinkTree(
          getPrivateCxxHeaders(targetNode),
          getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PRIVATE),
          arg.xcodePrivateHeadersSymlinks.orElse(true) || isHeaderMapDisabled(),
          !isHeaderMapDisabled());
    }
    if (shouldMergeHeaderMaps() && isMainProject) {
      createMergedHeaderMap();
    }

    if (appleTargetNode.isPresent() &&
        isFocusedOnTarget &&
        !shouldGenerateHeaderSymlinkTreesOnly()) {
      // Use Core Data models from immediate dependencies only.
      addCoreDataModelsIntoTarget(appleTargetNode.get(), targetGroup.get());
      addSceneKitAssetsIntoTarget(appleTargetNode.get(), targetGroup.get());
    }

    if (bundle.isPresent() &&
        isFocusedOnTarget &&
        !shouldGenerateHeaderSymlinkTreesOnly()) {
      addEntitlementsPlistIntoTarget(bundle.get(), targetGroup.get());
    }

    return target;
  }

  private ImmutableMap<String, String> getFrameworkAndLibrarySearchPathConfigs(
      TargetNode<?, ?> node) {
    HashSet<String> frameworkSearchPaths = new HashSet<>();
    frameworkSearchPaths.add("$BUILT_PRODUCTS_DIR");
    HashSet<String> librarySearchPaths = new HashSet<>();
    librarySearchPaths.add("$BUILT_PRODUCTS_DIR");
    HashSet<String> ldRunpathSearchPaths = new HashSet<>();

    Stream.concat(
        // Collect all the nodes that contribute to linking
        // ... Which the node includes itself
        Stream.of(node),
        // ... And recursive dependencies that gets linked in
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.LINKING,
            node,
            ImmutableSet.of(
                AppleLibraryDescription.class,
                CxxLibraryDescription.class,
                PrebuiltAppleFrameworkDescription.class)).stream())
        // Keep only the ones that may have frameworks and libraries fields.
        .flatMap(input -> RichStream.from(input.castArg(HasSystemFrameworkAndLibraries.class)))
        // Then for each of them
        .forEach(castedNode -> {
          // ... Add the framework path strings.
          castedNode.getConstructorArg().getFrameworks()
              .stream()
              .map(frameworkPath ->
                  FrameworkPath.getUnexpandedSearchPath(
                      this::resolveSourcePath,
                      pathRelativizer::outputDirToRootRelative,
                      frameworkPath).toString())
              .forEach(frameworkSearchPaths::add);

          // ... And do the same for libraries.
          castedNode.getConstructorArg().getLibraries()
              .stream()
              .map(libraryPath ->
                  FrameworkPath.getUnexpandedSearchPath(
                      this::resolveSourcePath,
                      pathRelativizer::outputDirToRootRelative,
                      libraryPath).toString())
              .forEach(librarySearchPaths::add);

          // If the item itself is a prebuilt framework, add it to framework_search_paths.
          // This is needed for prebuilt framework's headers to be reference-able.
          castedNode.castArg(PrebuiltAppleFrameworkDescription.Arg.class).ifPresent(prebuilt -> {
              frameworkSearchPaths.add(
                  "$REPO_ROOT/" +
                  resolveSourcePath(prebuilt.getConstructorArg().framework)
                      .getParent());
              if (prebuilt.getConstructorArg().preferredLinkage != NativeLinkable.Linkage.STATIC) {
                // Frameworks that are copied into the binary.
                ldRunpathSearchPaths.add("@executable_path/Frameworks");
              }
          });
        });


    ImmutableMap.Builder<String, String> results = ImmutableMap.<String, String>builder()
        .put("FRAMEWORK_SEARCH_PATHS", Joiner.on(' ').join(frameworkSearchPaths))
        .put("LIBRARY_SEARCH_PATHS", Joiner.on(' ').join(librarySearchPaths));
    if (!ldRunpathSearchPaths.isEmpty()) {
      results.put(
          "LD_RUNPATH_SEARCH_PATHS",
          Joiner.on(' ').join(ldRunpathSearchPaths));
    }
    return results.build();
  }

  public static String getProductName(TargetNode<?, ?> buildTargetNode, BuildTarget buildTarget) {
    return getProductNameForTargetNode(buildTargetNode).orElse(getProductNameForBuildTarget(
        buildTarget));
  }

  private ImmutableSortedMap<Path, SourcePath> getPublicCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode) {
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(
          (AppleNativeTargetDescriptionArg) arg,
          targetNode.getBuildTarget());
      ImmutableSortedMap<String, SourcePath> cxxHeaders =
          AppleDescriptions.convertAppleHeadersToPublicCxxHeaders(
              this::resolveSourcePath,
              headerPathPrefix,
              arg);
      return convertMapKeysToPaths(cxxHeaders);
    } else {
      BuildRuleResolver resolver = buildRuleResolverForNode.apply(targetNode);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
      try {
        return ImmutableSortedMap.copyOf(
            CxxDescriptionEnhancer.parseExportedHeaders(
                targetNode.getBuildTarget(),
                resolver,
                ruleFinder,
                pathResolver,
                Optional.empty(),
                arg));
      } catch (NoSuchBuildTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private ImmutableSortedMap<Path, SourcePath> getPrivateCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode) {
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(
          (AppleNativeTargetDescriptionArg) arg,
          targetNode.getBuildTarget());
      ImmutableSortedMap<String, SourcePath> cxxHeaders =
          AppleDescriptions.convertAppleHeadersToPrivateCxxHeaders(
              this::resolveSourcePath,
              headerPathPrefix,
              arg);
      return convertMapKeysToPaths(cxxHeaders);
    } else {
      BuildRuleResolver resolver = buildRuleResolverForNode.apply(targetNode);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
      try {
        return ImmutableSortedMap.copyOf(
            CxxDescriptionEnhancer.parseHeaders(
                targetNode.getBuildTarget(),
                resolver,
                ruleFinder,
                pathResolver,
                Optional.empty(),
                arg));
      } catch (NoSuchBuildTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private ImmutableSortedMap<Path, SourcePath> convertMapKeysToPaths(
      ImmutableSortedMap<String, SourcePath> input) {
    ImmutableSortedMap.Builder<Path, SourcePath> output = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, SourcePath> entry : input.entrySet()) {
      output.put(Paths.get(entry.getKey()), entry.getValue());
    }
    return output.build();
  }

  private Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>>
  getXcodeBuildConfigurationsForTargetNode(
      TargetNode<?, ?> targetNode,
      ImmutableMap<String, String> appendedConfig) {
    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs = Optional.empty();
    Optional<TargetNode<AppleNativeTargetDescriptionArg, ?>> appleTargetNode =
        targetNode.castArg(AppleNativeTargetDescriptionArg.class);
    Optional<TargetNode<HalideLibraryDescription.Arg, ?>> halideTargetNode =
        targetNode.castArg(HalideLibraryDescription.Arg.class);
    if (appleTargetNode.isPresent()) {
      configs = Optional.of(appleTargetNode.get().getConstructorArg().configs);
    } else if (halideTargetNode.isPresent()) {
      configs = Optional.of(halideTargetNode.get().getConstructorArg().configs);
    }
    if (!configs.isPresent() ||
        (configs.isPresent() && configs.get().isEmpty()) ||
        targetNode.getDescription() instanceof CxxLibraryDescription) {
      ImmutableMap<String, ImmutableMap<String, String>> defaultConfig =
          CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
              defaultCxxPlatform,
              appendedConfig);
      configs = Optional.of(ImmutableSortedMap.copyOf(defaultConfig));
    }
    return configs;
  }

  private void addEntitlementsPlistIntoTarget(
      TargetNode<? extends HasAppleBundleFields, ?> targetNode,
      PBXGroup targetGroup) throws IOException {
    ImmutableMap<String, String> infoPlistSubstitutions =
        targetNode.getConstructorArg().getInfoPlistSubstitutions();

    if (infoPlistSubstitutions.containsKey(AppleBundle.CODE_SIGN_ENTITLEMENTS)) {
      String entitlementsPlistPath =
          InfoPlistSubstitution.replaceVariablesInString(
              "$(" + AppleBundle.CODE_SIGN_ENTITLEMENTS + ")",
              AppleBundle.withDefaults(
                  infoPlistSubstitutions,
                  ImmutableMap.of(
                      "SOURCE_ROOT", ".",
                      "SRCROOT", "."
                  )));

      targetGroup.getOrCreateFileReferenceBySourceTreePath(
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              Paths.get(entitlementsPlistPath),
              Optional.empty()));
    }
  }

  private void addCoreDataModelsIntoTarget(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      PBXGroup targetGroup) throws IOException {
    addCoreDataModelBuildPhase(
        targetGroup,
        AppleBuildRules.collectTransitiveBuildRules(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode)));
  }

  private void addSceneKitAssetsIntoTarget(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      PBXGroup targetGroup) throws IOException {
    ImmutableSet<AppleWrapperResourceArg> allSceneKitAssets =
        AppleBuildRules.collectTransitiveBuildRules(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.SCENEKIT_ASSETS_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode));

    for (final AppleWrapperResourceArg sceneKitAssets : allSceneKitAssets) {
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              pathRelativizer.outputDirToRootRelative(sceneKitAssets.path),
              Optional.empty()));
    }
  }

  private Path getConfigurationXcconfigPath(BuildTarget buildTarget, String input) {
    return BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s-" + input + ".xcconfig");
  }

  private Iterable<SourcePath> getHeaderSourcePaths(SourceList headers) {
    if (headers.getUnnamedSources().isPresent()) {
      return headers.getUnnamedSources().get();
    } else {
      return headers.getNamedSources().get().values();
    }
  }

  /**
   * Create target level configuration entries.
   *
   * @param target      Xcode target for which the configurations will be set.
   * @param targetGroup Xcode group in which the configuration file references will be placed.
   * @param configurations  Configurations as extracted from the BUCK file.
   * @param overrideBuildSettings Build settings that will override ones defined elsewhere.
   * @param defaultBuildSettings  Target-inline level build settings that will be set if not already
   *                              defined.
   * @param appendBuildSettings   Target-inline level build settings that will incorporate the
   *                              existing value or values at a higher level.
   */
  private void setTargetBuildConfigurations(
      BuildTarget buildTarget,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableMap<String, ImmutableMap<String, String>> configurations,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> defaultBuildSettings,
      ImmutableMap<String, String> appendBuildSettings)
      throws IOException {
    if (shouldGenerateHeaderSymlinkTreesOnly()) {
      return;
    }

    for (Map.Entry<String, ImmutableMap<String, String>> configurationEntry :
        configurations.entrySet()) {
      targetConfigNamesBuilder.add(configurationEntry.getKey());

      ImmutableMap<String, String> targetLevelInlineSettings =
          configurationEntry.getValue();

      XCBuildConfiguration outputConfiguration = target
          .getBuildConfigurationList()
          .getBuildConfigurationsByName()
          .getUnchecked(configurationEntry.getKey());

      HashMap<String, String> combinedOverrideConfigs = Maps.newHashMap(overrideBuildSettings);
      for (Map.Entry<String, String> entry: defaultBuildSettings.entrySet()) {
        String existingSetting = targetLevelInlineSettings.get(entry.getKey());
        if (existingSetting == null) {
          combinedOverrideConfigs.put(entry.getKey(), entry.getValue());
        }
      }

      for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
        String existingSetting = targetLevelInlineSettings.get(entry.getKey());
        String settingPrefix = existingSetting != null ? existingSetting : "$(inherited)";
        combinedOverrideConfigs.put(entry.getKey(), settingPrefix + " " + entry.getValue());
      }

      ImmutableSortedMap<String, String> mergedSettings = MoreMaps.mergeSorted(
          targetLevelInlineSettings,
          combinedOverrideConfigs);
      Path xcconfigPath = getConfigurationXcconfigPath(buildTarget, configurationEntry.getKey());
      projectFilesystem.mkdirs(Preconditions.checkNotNull(xcconfigPath).getParent());

      StringBuilder stringBuilder = new StringBuilder();
      for (Map.Entry<String, String> entry : mergedSettings.entrySet()) {
        stringBuilder.append(entry.getKey());
        stringBuilder.append(" = ");
        stringBuilder.append(entry.getValue());
        stringBuilder.append('\n');
      }
      String xcconfigContents = stringBuilder.toString();

      if (MoreProjectFilesystems.fileContentsDiffer(
          new ByteArrayInputStream(xcconfigContents.getBytes(Charsets.UTF_8)),
          xcconfigPath,
          projectFilesystem)) {
        if (shouldGenerateReadOnlyFiles()) {
          projectFilesystem.writeContentsToPath(
              xcconfigContents,
              xcconfigPath,
              READ_ONLY_FILE_ATTRIBUTE);
        } else {
          projectFilesystem.writeContentsToPath(
              xcconfigContents,
              xcconfigPath);
        }
      }

      PBXFileReference fileReference = getConfigurationFileReference(targetGroup, xcconfigPath);
      outputConfiguration.setBaseConfigurationReference(fileReference);
    }
  }

  private PBXFileReference getConfigurationFileReference(PBXGroup targetGroup, Path xcconfigPath) {
    return targetGroup
        .getOrCreateChildGroupByName("Configurations")
        .getOrCreateChildGroupByName("Buck (Do Not Modify)")
        .getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(xcconfigPath),
                Optional.empty()));
  }

  private void collectBuildScriptDependencies(
      Iterable<TargetNode<?, ?>> targetNodes,
      ImmutableList.Builder<TargetNode<?, ?>> preRules,
      ImmutableList.Builder<TargetNode<?, ?>> postRules) {
    for (TargetNode<?, ?> targetNode : targetNodes) {
      if (targetNode.getDescription() instanceof IosReactNativeLibraryDescription) {
        postRules.add(targetNode);
        requiredBuildTargetsBuilder.add(targetNode.getBuildTarget());
      } else if (targetNode.getDescription() instanceof XcodePostbuildScriptDescription) {
        postRules.add(targetNode);
      } else if (targetNode.getDescription() instanceof XcodePrebuildScriptDescription) {
        preRules.add(targetNode);
      }
    }
  }

  /**
   * Adds the set of headers defined by headerVisibility to the merged header maps.
   */
  private void addToMergedHeaderMap(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      HeaderMap.Builder headerMapBuilder,
      HeaderVisibility headerVisibility) {
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    boolean shouldCreateHeadersSymlinks;
    Map<Path, SourcePath> contents;
    switch (headerVisibility) {
      case PUBLIC:
        shouldCreateHeadersSymlinks = arg.xcodePublicHeadersSymlinks.orElse(true);
        contents = getPublicCxxHeaders(targetNode);
        break;
      case PRIVATE:
        shouldCreateHeadersSymlinks = arg.xcodePrivateHeadersSymlinks.orElse(true);
        contents = getPrivateCxxHeaders(targetNode);
        break;
      default:
        throw new IllegalStateException("unhandled header visibility type: " + headerVisibility);
    }
    Path headerSymlinkTreeRoot = getPathToHeaderSymlinkTree(targetNode, headerVisibility);

    Path basePath;
    if (shouldCreateHeadersSymlinks) {
      basePath = projectFilesystem.getRootPath()
          .resolve(targetNode.getBuildTarget().getCellPath())
          .resolve(headerSymlinkTreeRoot);
    } else {
      basePath = projectFilesystem.getRootPath()
          .resolve(targetNode.getBuildTarget().getCellPath());
    }
    for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
      Path path;
      if (shouldCreateHeadersSymlinks) {
        path = basePath.resolve(entry.getKey());
      } else {
        path = basePath.resolve(resolveSourcePath(entry.getValue()));
      }
      headerMapBuilder.add(entry.getKey().toString(), path);
    }
  }

  /**
   * Generates the merged header maps and write it to the public header symlink tree location.
   */
  private void createMergedHeaderMap() throws IOException {
    HeaderMap.Builder headerMapBuilder = new HeaderMap.Builder();

    Set<TargetNode<? extends CxxLibraryDescription.Arg, ?>> processedNodes = new HashSet<>();

    for (TargetNode<?, ?> targetNode : targetGraph.getAll(targetsInRequiredProjects)) {
      // Includes the public headers of the dependencies in the merged header map.
      getAppleNativeNode(targetGraph, targetNode).ifPresent(argTargetNode ->
          visitRecursiveHeaderSymlinkTrees(
              argTargetNode,
              (depNativeNode, headerVisibility) -> {
                if (processedNodes.contains(depNativeNode)) {
                  return;
                }
                if (headerVisibility == HeaderVisibility.PUBLIC) {
                  addToMergedHeaderMap(
                      depNativeNode,
                      headerMapBuilder,
                      HeaderVisibility.PUBLIC);
                  processedNodes.add(depNativeNode);
                }
              }));
    }

    // Writes the resulting header map.
    Path mergedHeaderMapRoot = getPathToMergedHeaderMap();
    Path headerMapLocation = getHeaderMapLocationFromSymlinkTreeRoot(mergedHeaderMapRoot);
    projectFilesystem.mkdirs(mergedHeaderMapRoot);
    projectFilesystem.writeBytesToPath(headerMapBuilder.build().getBytes(), headerMapLocation);
  }

  private void createHeaderSymlinkTree(
      Map<Path, SourcePath> contents,
      Path headerSymlinkTreeRoot,
      boolean shouldCreateHeadersSymlinks,
      boolean shouldCreateHeaderMap) throws IOException {
    if (!shouldCreateHeaderMap && !shouldCreateHeadersSymlinks) {
      return;
    }
    LOG.verbose(
        "Building header symlink tree at %s with contents %s",
        headerSymlinkTreeRoot,
        contents);
    ImmutableSortedMap.Builder<Path, Path> resolvedContentsBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
      Path link = headerSymlinkTreeRoot.resolve(entry.getKey());
      Path existing = projectFilesystem.resolve(resolveSourcePath(entry.getValue()));
      resolvedContentsBuilder.put(link, existing);
    }
    ImmutableSortedMap<Path, Path> resolvedContents = resolvedContentsBuilder.build();

    Path headerMapLocation = getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);

    Path hashCodeFilePath = headerSymlinkTreeRoot.resolve(".contents-hash");
    Optional<String> currentHashCode = projectFilesystem.readFileIfItExists(hashCodeFilePath);
    String newHashCode =
        getHeaderSymlinkTreeHashCode(
            resolvedContents, shouldCreateHeadersSymlinks, shouldCreateHeaderMap).toString();
    if (Optional.of(newHashCode).equals(currentHashCode)) {
      LOG.debug(
          "Symlink tree at %s is up to date, not regenerating (key %s).",
          headerSymlinkTreeRoot,
          newHashCode);
    } else {
      LOG.debug(
          "Updating symlink tree at %s (old key %s, new key %s).",
          headerSymlinkTreeRoot,
          currentHashCode,
          newHashCode);
      projectFilesystem.deleteRecursivelyIfExists(headerSymlinkTreeRoot);
      projectFilesystem.mkdirs(headerSymlinkTreeRoot);
      if (shouldCreateHeadersSymlinks) {
        for (Map.Entry<Path, Path> entry : resolvedContents.entrySet()) {
          Path link = entry.getKey();
          Path existing = entry.getValue();
          projectFilesystem.createParentDirs(link);
          projectFilesystem.createSymLink(link, existing, /* force */ false);
        }
      }
      projectFilesystem.writeContentsToPath(newHashCode, hashCodeFilePath);

      if (shouldCreateHeaderMap) {
        HeaderMap.Builder headerMapBuilder = new HeaderMap.Builder();
        for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
          if (shouldCreateHeadersSymlinks) {
            headerMapBuilder.add(
                entry.getKey().toString(),
                Paths.get("../../")
                    .resolve(projectCell.getRoot().getFileName())
                    .resolve(headerSymlinkTreeRoot)
                    .resolve(entry.getKey()));
          } else {
            headerMapBuilder.add(
                entry.getKey().toString(),
                projectFilesystem.resolve(resolveSourcePath(entry.getValue())));
          }
        }
        projectFilesystem.writeBytesToPath(headerMapBuilder.build().getBytes(), headerMapLocation);
      }
    }
    headerSymlinkTrees.add(headerSymlinkTreeRoot);
  }

  private HashCode getHeaderSymlinkTreeHashCode(
      ImmutableSortedMap<Path, Path> contents,
      boolean shouldCreateHeadersSymlinks,
      boolean shouldCreateHeaderMap) {
    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(BuckVersion.getVersion().getBytes(Charsets.UTF_8));
    String symlinkState = shouldCreateHeadersSymlinks ? "symlinks-enabled" : "symlinks-disabled";
    byte[] symlinkStateValue = symlinkState.getBytes(Charsets.UTF_8);
    hasher.putInt(symlinkStateValue.length);
    hasher.putBytes(symlinkStateValue);
    String hmapState = shouldCreateHeaderMap ? "hmap-enabled" : "hmap-disabled";
    byte[] hmapStateValue = hmapState.getBytes(Charsets.UTF_8);
    hasher.putInt(hmapStateValue.length);
    hasher.putBytes(hmapStateValue);
    hasher.putInt(0);
    for (Map.Entry<Path, Path> entry : contents.entrySet()) {
      byte[] key = entry.getKey().toString().getBytes(Charsets.UTF_8);
      byte[] value = entry.getValue().toString().getBytes(Charsets.UTF_8);
      hasher.putInt(key.length);
      hasher.putBytes(key);
      hasher.putInt(value.length);
      hasher.putBytes(value);
    }
    return hasher.hash();
  }

  private void addCoreDataModelBuildPhase(
      PBXGroup targetGroup,
      Iterable<AppleWrapperResourceArg> dataModels) throws IOException {
    // TODO(coneko): actually add a build phase

    for (final AppleWrapperResourceArg dataModel : dataModels) {
      // Core data models go in the resources group also.
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      if (CoreDataModelDescription.isVersionedDataModel(dataModel)) {
        // It's safe to do I/O here to figure out the current version because we're returning all
        // the versions and the file pointing to the current version from
        // getInputsToCompareToOutput(), so the rule will be correctly detected as stale if any of
        // them change.
        final String currentVersionFileName = ".xccurrentversion";
        final String currentVersionKey = "_XCCurrentVersionName";

        final XCVersionGroup versionGroup =
            resourcesGroup.getOrCreateChildVersionGroupsBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(dataModel.path),
                    Optional.empty()));

        projectFilesystem.walkRelativeFileTree(
            dataModel.path,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(dataModel.path)) {
                  return FileVisitResult.CONTINUE;
                }
                versionGroup.getOrCreateFileReferenceBySourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.SOURCE_ROOT,
                        pathRelativizer.outputDirToRootRelative(dir),
                        Optional.empty()));
                return FileVisitResult.SKIP_SUBTREE;
              }
            });

        Path currentVersionPath = dataModel.path.resolve(currentVersionFileName);
        try (InputStream in = projectFilesystem.newFileInputStream(currentVersionPath)) {
          NSObject rootObject;
          try {
            rootObject = PropertyListParser.parse(in);
          } catch (IOException e) {
            throw e;
          } catch (Exception e) {
            rootObject = null;
          }
          if (!(rootObject instanceof NSDictionary)) {
            throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
          }
          NSDictionary rootDictionary = (NSDictionary) rootObject;
          NSObject currentVersionName = rootDictionary.objectForKey(currentVersionKey);
          if (!(currentVersionName instanceof NSString)) {
            throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
          }
          PBXFileReference ref = versionGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(
                      dataModel.path.resolve(currentVersionName.toString())),
                  Optional.empty()));
          versionGroup.setCurrentVersion(Optional.of(ref));
        } catch (NoSuchFileException e) {
          if (versionGroup.getChildren().size() == 1) {
            versionGroup.setCurrentVersion(Optional.of(Iterables.get(
                versionGroup.getChildren(),
                0)));
          }
        }
      } else {
        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(dataModel.path),
                Optional.empty()));
      }
    }
  }

  private Optional<CopyFilePhaseDestinationSpec> getDestinationSpec(TargetNode<?, ?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      AppleBundleExtension extension = arg.extension.isLeft() ?
          arg.extension.getLeft() :
          AppleBundleExtension.BUNDLE;
      switch (extension) {
        case FRAMEWORK:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS)
          );
        case APPEX:
        case PLUGIN:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS)
          );
        case APP:
          if (isWatchApplicationNode(targetNode)) {
            return Optional.of(
                CopyFilePhaseDestinationSpec.builder()
                    .setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
                    .setPath("$(CONTENTS_FOLDER_PATH)/Watch")
                    .build()
            );
          } else {
            return Optional.of(
                CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES)
            );
          }
        case BUNDLE:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS)
          );
        //$CASES-OMITTED$
        default:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
          );
      }
    } else if (
        targetNode.getDescription() instanceof AppleLibraryDescription ||
        targetNode.getDescription() instanceof CxxLibraryDescription) {
      if (targetNode
          .getBuildTarget()
          .getFlavors()
          .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
        return Optional.of(
            CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS)
        );
      } else {
        return Optional.empty();
      }
    } else if (
        targetNode.getDescription() instanceof AppleBinaryDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES)
      );
    } else if (
        targetNode.getDescription() instanceof HalideLibraryDescription) {
      return Optional.empty();
    } else if (
        targetNode.getDescription() instanceof CoreDataModelDescription ||
            targetNode.getDescription() instanceof SceneKitAssetsDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.RESOURCES)
      );
    } else if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }
  }

  /**
   * Convert a list of rules that should be somehow included into the bundle, into build phases
   * which copies them into the bundle. The parameters of these copy phases are divined by
   * scrutinizing the type of node we want to include.
   */
  private ImmutableList<PBXBuildPhase> getCopyFilesBuildPhases(
      Iterable<TargetNode<?, ?>> copiedNodes) {

    // Bucket build rules into bins by their destinations
    ImmutableSetMultimap.Builder<CopyFilePhaseDestinationSpec, TargetNode<?, ?>>
        ruleByDestinationSpecBuilder = ImmutableSetMultimap.builder();
    for (TargetNode<?, ?> copiedNode : copiedNodes) {
      getDestinationSpec(copiedNode).ifPresent(copyFilePhaseDestinationSpec ->
          ruleByDestinationSpecBuilder.put(copyFilePhaseDestinationSpec, copiedNode));
    }

    ImmutableList.Builder<PBXBuildPhase> phases = ImmutableList.builder();

    ImmutableSetMultimap<CopyFilePhaseDestinationSpec, TargetNode<?, ?>> ruleByDestinationSpec =
        ruleByDestinationSpecBuilder.build();

    // Emit a copy files phase for each destination.
    for (CopyFilePhaseDestinationSpec destinationSpec : ruleByDestinationSpec.keySet()) {
      Iterable<TargetNode<?, ?>> targetNodes = ruleByDestinationSpec.get(destinationSpec);
      phases.add(getSingleCopyFilesBuildPhase(destinationSpec, targetNodes));
    }

    return phases.build();
  }

  private PBXCopyFilesBuildPhase getSingleCopyFilesBuildPhase(
      CopyFilePhaseDestinationSpec destinationSpec,
      Iterable<TargetNode<?, ?>> targetNodes) {
    PBXCopyFilesBuildPhase copyFilesBuildPhase = new PBXCopyFilesBuildPhase(destinationSpec);
    HashSet<UnflavoredBuildTarget> frameworkTargets = new HashSet<UnflavoredBuildTarget>();

    for (TargetNode<?, ?> targetNode : targetNodes) {
      PBXFileReference fileReference = getLibraryFileReference(targetNode);
      PBXBuildFile buildFile = new PBXBuildFile(fileReference);
      if (fileReference.getExplicitFileType().equals(Optional.of("wrapper.framework"))) {
        UnflavoredBuildTarget buildTarget = targetNode.getBuildTarget().getUnflavoredBuildTarget();
        if (frameworkTargets.contains(buildTarget)) {
          continue;
        }
        frameworkTargets.add(buildTarget);

        NSDictionary settings = new NSDictionary();
        settings.put("ATTRIBUTES", new String[] {"CodeSignOnCopy", "RemoveHeadersOnCopy"});
        buildFile.setSettings(Optional.of(settings));
      }
      copyFilesBuildPhase.getFiles().add(buildFile);
    }
    return copyFilesBuildPhase;
  }

  /**
   * Create the project bundle structure and write {@code project.pbxproj}.
   */
  private Path writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(
        new GidGenerator(ImmutableSet.copyOf(gidsToTargetNames.keySet())),
        project);
    NSDictionary rootObject = serializer.toPlist();
    Path xcodeprojDir = outputDirectory.resolve(projectName + ".xcodeproj");
    projectFilesystem.mkdirs(xcodeprojDir);
    Path serializedProject = xcodeprojDir.resolve("project.pbxproj");
    String contentsToWrite = rootObject.toXMLPropertyList();
    // Before we write any files, check if the file contents have changed.
    if (MoreProjectFilesystems.fileContentsDiffer(
        new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
        serializedProject,
        projectFilesystem)) {
      LOG.debug("Regenerating project at %s", serializedProject);
      if (shouldGenerateReadOnlyFiles()) {
        projectFilesystem.writeContentsToPath(
            contentsToWrite,
            serializedProject,
            READ_ONLY_FILE_ATTRIBUTE);
      } else {
        projectFilesystem.writeContentsToPath(
            contentsToWrite,
            serializedProject);
      }
    } else {
      LOG.debug("Not regenerating project at %s (contents have not changed)", serializedProject);
    }
    return xcodeprojDir;
  }

  private static String getProductNameForBuildTarget(BuildTarget buildTarget) {
    return buildTarget.getShortName();
  }

  /**
   * @param targetNode Must have a header symlink tree or an exception will be thrown.
   */
  private Path getHeaderSymlinkTreeRelativePath(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      HeaderVisibility headerVisibility) {
    Path treeRoot = getPathToHeaderSymlinkTree(
        targetNode,
        headerVisibility);
    Path cellRoot = MorePaths.relativize(
        projectFilesystem.getRootPath(),
        targetNode.getBuildTarget().getCellPath());
    return pathRelativizer.outputDirToRootRelative(cellRoot.resolve(treeRoot));
  }

  private Path getHeaderMapLocationFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    return headerSymlinkTreeRoot.resolve(".hmap");
  }

  private Path getHeaderSearchPathFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    if (isHeaderMapDisabled()) {
      return headerSymlinkTreeRoot;
    } else {
      return getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);
    }
  }

  private Path getRelativePathToMergedHeaderMap() {
    Path treeRoot = getPathToMergedHeaderMap();
    Path cellRoot = MorePaths.relativize(
        projectFilesystem.getRootPath(),
        workspaceTarget.get().getCellPath());
    return pathRelativizer.outputDirToRootRelative(cellRoot.resolve(treeRoot));
  }

  private String getBuiltProductsRelativeTargetOutputPath(TargetNode<?, ?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBinaryDescription ||
        targetNode.getDescription() instanceof AppleTestDescription ||
        (targetNode.getDescription() instanceof AppleBundleDescription &&
            !isFrameworkBundle((AppleBundleDescription.Arg) targetNode.getConstructorArg()))) {
      // TODO(grp): These should be inside the path below. Right now, that causes issues with
      // bundle loader paths hardcoded in .xcconfig files that don't expect the full target path.
      // It also causes issues where Xcode doesn't know where to look for a final .app to run it.
      return ".";
    } else {
      return BaseEncoding
          .base32()
          .omitPadding()
          .encode(targetNode.getBuildTarget().getFullyQualifiedName().getBytes());
    }
  }

  private String getTargetOutputPath(TargetNode<?, ?> targetNode) {
    return Joiner.on('/').join(
        "$BUILT_PRODUCTS_DIR",
        getBuiltProductsRelativeTargetOutputPath(targetNode));
  }

  @SuppressWarnings("unchecked")
  private static Optional<TargetNode<CxxLibraryDescription.Arg, ?>> getAppleNativeNodeOfType(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode,
      Set<Class<? extends Description<?>>> nodeTypes,
      Set<AppleBundleExtension> bundleExtensions) {
    Optional<TargetNode<CxxLibraryDescription.Arg, ?>> nativeNode = Optional.empty();
    if (nodeTypes.contains(targetNode.getDescription().getClass())) {
      nativeNode = Optional.of((TargetNode<CxxLibraryDescription.Arg, ?>) targetNode);
    } else if (
        targetNode.getDescription() instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescription.Arg, ?> bundle =
          (TargetNode<AppleBundleDescription.Arg, ?>) targetNode;
      Either<AppleBundleExtension, String> extension = bundle.getConstructorArg().getExtension();
      if (extension.isLeft() && bundleExtensions.contains(extension.getLeft())) {
        nativeNode = Optional.of(
            (TargetNode<CxxLibraryDescription.Arg, ?>) targetGraph.get(
                bundle.getConstructorArg().binary));
      }
    }
    return nativeNode;
  }

  private static Optional<TargetNode<CxxLibraryDescription.Arg, ?>> getAppleNativeNode(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        APPLE_NATIVE_DESCRIPTION_CLASSES,
        APPLE_NATIVE_BUNDLE_EXTENSIONS);
  }

  private static Optional<TargetNode<CxxLibraryDescription.Arg, ?>> getLibraryNode(
      TargetGraph targetGraph,
      TargetNode<?, ?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(
            AppleLibraryDescription.class,
            CxxLibraryDescription.class),
        ImmutableSet.of(
            AppleBundleExtension.FRAMEWORK));
  }

  private ImmutableSet<Path> collectRecursiveHeaderSearchPaths(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    if (shouldMergeHeaderMaps()) {
      builder.add(getHeaderSearchPathFromSymlinkTreeRoot(getHeaderSymlinkTreeRelativePath(
          targetNode,
          HeaderVisibility.PRIVATE)));
      builder.add(getHeaderSearchPathFromSymlinkTreeRoot(
          getRelativePathToMergedHeaderMap()));
      visitRecursivePrivateHeaderSymlinkTreesForTests(targetNode,
          (nativeNode, headerVisibility) -> {
            builder.add(getHeaderSearchPathFromSymlinkTreeRoot(getHeaderSymlinkTreeRelativePath(
                nativeNode,
                headerVisibility)));
          });
    } else {
      for (Path headerSymlinkTreePath : collectRecursiveHeaderSymlinkTrees(targetNode)) {
        builder.add(getHeaderSearchPathFromSymlinkTreeRoot(headerSymlinkTreePath));
      }
    }

    for (Path halideHeaderPath : collectRecursiveHalideLibraryHeaderPaths(targetNode)) {
      builder.add(halideHeaderPath);
    }

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private ImmutableSet<Path> collectRecursiveHalideLibraryHeaderPaths(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    for (TargetNode<?, ?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(
                ImmutableSet.of(HalideLibraryDescription.class)))) {
        TargetNode<HalideLibraryDescription.Arg, ?> halideNode =
            (TargetNode<HalideLibraryDescription.Arg, ?>) input;
        BuildTarget buildTarget = halideNode.getBuildTarget();
        builder.add(
            pathRelativizer.outputDirToRootRelative(
                HalideCompile
                    .headerOutputPath(
                        buildTarget.withFlavors(
                            HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
                            defaultCxxPlatform.getFlavor()),
                        projectFilesystem,
                        halideNode.getConstructorArg().functionName)
                    .getParent()));
    }
    return builder.build();
  }

  private void visitRecursiveHeaderSymlinkTrees(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      BiConsumer<TargetNode<? extends CxxLibraryDescription.Arg, ?>, HeaderVisibility> visitor) {
    // Visits public and private headers from current target.
    visitor.accept(targetNode, HeaderVisibility.PRIVATE);
    visitor.accept(targetNode, HeaderVisibility.PUBLIC);

    // Visits public headers from dependencies.
    for (TargetNode<?, ?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(AppleBuildRules.XCODE_TARGET_DESCRIPTION_CLASSES))) {
      getAppleNativeNode(targetGraph, input).ifPresent(argTargetNode ->
          visitor.accept(argTargetNode, HeaderVisibility.PUBLIC));
    }

    visitRecursivePrivateHeaderSymlinkTreesForTests(targetNode, visitor);
  }

  private void visitRecursivePrivateHeaderSymlinkTreesForTests(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      BiConsumer<TargetNode<? extends CxxLibraryDescription.Arg, ?>, HeaderVisibility> visitor) {
    // Visits headers of source under tests.
    ImmutableSet<TargetNode<?, ?>> directDependencies = ImmutableSet.copyOf(
        targetGraph.getAll(targetNode.getBuildDeps()));
    for (TargetNode<?, ?> dependency : directDependencies) {
      Optional<TargetNode<CxxLibraryDescription.Arg, ?>> nativeNode =
          getAppleNativeNode(targetGraph, dependency);
      if (nativeNode.isPresent() && isSourceUnderTest(dependency, nativeNode.get(), targetNode)) {
        visitor.accept(nativeNode.get(), HeaderVisibility.PRIVATE);
      }
    }
  }

  private ImmutableSet<Path> collectRecursiveHeaderSymlinkTrees(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    visitRecursiveHeaderSymlinkTrees(targetNode, (nativeNode, headerVisibility) -> {
      builder.add(
          getHeaderSymlinkTreeRelativePath(
              nativeNode,
              headerVisibility));
    });
    return builder.build();
  }

  private boolean isSourceUnderTest(
      TargetNode<?, ?> dependencyNode,
      TargetNode<CxxLibraryDescription.Arg, ?> nativeNode,
      TargetNode<?, ?> testNode) {
    boolean isSourceUnderTest =
        nativeNode.getConstructorArg().getTests().contains(testNode.getBuildTarget());

    if (dependencyNode != nativeNode && dependencyNode.getConstructorArg() instanceof HasTests) {
      ImmutableSortedSet<BuildTarget> tests =
          ((HasTests) dependencyNode.getConstructorArg()).getTests();
      if (tests.contains(testNode.getBuildTarget())) {
        isSourceUnderTest = true;
      }
    }

    return isSourceUnderTest;
  }

  /**
   * List of frameworks and libraries that goes into the "Link Binary With Libraries" phase.
   */
  private Iterable<FrameworkPath> collectRecursiveFrameworkDependencies(
      TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.<Class<? extends Description<?>>>builder()
                    .addAll(AppleBuildRules.XCODE_TARGET_DESCRIPTION_CLASSES)
                    .add(PrebuiltAppleFrameworkDescription.class)
                    .build()))
        .transformAndConcat(input -> {
          // Libraries and bundles which has system frameworks and libraries.
          Optional<TargetNode<CxxLibraryDescription.Arg, ?>> library =
              getLibraryNode(targetGraph, input);
          if (library.isPresent() && !AppleLibraryDescription.isSharedLibraryNode(library.get())) {
            return Iterables.concat(
                library.get().getConstructorArg().getFrameworks(),
                library.get().getConstructorArg().getLibraries());
          }

          Optional<TargetNode<PrebuiltAppleFrameworkDescription.Arg, ?>> prebuilt =
              input.castArg(PrebuiltAppleFrameworkDescription.Arg.class);
          if (prebuilt.isPresent()) {
            return Iterables.concat(
                prebuilt.get().getConstructorArg().getFrameworks(),
                prebuilt.get().getConstructorArg().getLibraries(),
                ImmutableList.of(
                    FrameworkPath.ofSourcePath(prebuilt.get().getConstructorArg().framework)));
          }

          return ImmutableList.of();
        });
  }

  private Iterable<String> collectRecursiveExportedPreprocessorFlags(TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input -> input.castArg(CxxLibraryDescription.Arg.class)
                .map(input1 -> input1.getConstructorArg().exportedPreprocessorFlags)
                .orElse(ImmutableList.of()));
  }

  private Iterable<Pair<Pattern, ImmutableList<String>>>
  collectRecursiveExportedPlatformPreprocessorFlags(TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(input ->
            input.castArg(CxxLibraryDescription.Arg.class)
                .map(input1 -> input1.getConstructorArg().exportedPlatformPreprocessorFlags
                    .getPatternsAndValues())
                .orElse(ImmutableList.of()));
  }

  private ImmutableList<StringWithMacros> collectRecursiveExportedLinkerFlags(
      TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    HalideLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(input ->
            input.castArg(CxxLibraryDescription.Arg.class)
                .map(input1 -> input1.getConstructorArg().exportedLinkerFlags)
                .orElse(ImmutableList.of()))
        .toList();
  }

  private Iterable<Pair<Pattern, ImmutableList<StringWithMacros>>>
  collectRecursiveExportedPlatformLinkerFlags(TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    HalideLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(input ->
            input.castArg(CxxLibraryDescription.Arg.class)
                .map(input1 -> input1.getConstructorArg().exportedPlatformLinkerFlags.
                    getPatternsAndValues())
                .orElse(ImmutableList.of()));
  }

  private ImmutableSet<PBXFileReference>
  collectRecursiveLibraryDependencies(TargetNode<?, ?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                AppleBuildRules.XCODE_TARGET_DESCRIPTION_CLASSES))
        .filter(this::isLibraryWithSourcesToCompile)
        .transform(this::getLibraryFileReference)
        .toSet();
  }

  private SourceTreePath getProductsSourceTreePath(TargetNode<?, ?> targetNode) {
    String productName = getProductNameForBuildTarget(targetNode.getBuildTarget());
    String productOutputName;

    if (targetNode.getDescription() instanceof AppleLibraryDescription ||
        targetNode.getDescription() instanceof CxxLibraryDescription ||
        targetNode.getDescription() instanceof HalideLibraryDescription) {
      String productOutputFormat = AppleBuildRules.getOutputFileNameFormatForLibrary(
          targetNode
              .getBuildTarget()
              .getFlavors()
              .contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
      productOutputName = String.format(productOutputFormat, productName);
    } else if (
        targetNode.getDescription() instanceof AppleBundleDescription ||
        targetNode.getDescription() instanceof AppleTestDescription) {
      HasAppleBundleFields arg = (HasAppleBundleFields) targetNode.getConstructorArg();
      productName = arg.getProductName().orElse(productName);
      productOutputName = productName + "." + getExtensionString(arg.getExtension());
    } else if (
        targetNode.getDescription() instanceof AppleBinaryDescription) {
      productOutputName = productName;
    } else if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      PrebuiltAppleFrameworkDescription.Arg arg =
          (PrebuiltAppleFrameworkDescription.Arg) targetNode.getConstructorArg();
      // Prebuilt frameworks reside in the source repo, not outputs dir.
      return new SourceTreePath(
          PBXReference.SourceTree.SOURCE_ROOT,
          pathRelativizer.outputPathToSourcePath(arg.framework),
          Optional.empty());
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }

    return new SourceTreePath(
        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
        Paths.get(productOutputName),
        Optional.empty());
  }

  private PBXFileReference getLibraryFileReference(TargetNode<?, ?> targetNode) {
    // Don't re-use the productReference from other targets in this project.
    // File references set as a productReference don't work with custom paths.
    SourceTreePath productsPath = getProductsSourceTreePath(targetNode);

    if (isWatchApplicationNode(targetNode)) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Products")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (
        targetNode.getDescription() instanceof AppleLibraryDescription ||
        targetNode.getDescription() instanceof AppleBundleDescription ||
        targetNode.getDescription() instanceof CxxLibraryDescription ||
        targetNode.getDescription() instanceof HalideLibraryDescription ||
        targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription
    ) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Frameworks")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (
        targetNode.getDescription() instanceof AppleBinaryDescription) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Dependencies")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }
  }

  /**
   * Whether a given build target is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildTarget buildTarget) {
    return initialTargets.contains(buildTarget);
  }

  private String getXcodeTargetName(BuildTarget target) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? target.getShortName()
        : target.getFullyQualifiedName();
  }

  ProductType bundleToTargetProductType(
      TargetNode<? extends HasAppleBundleFields, ?> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg, ?> binaryNode) {
    if (targetNode.getConstructorArg().getXcodeProductType().isPresent()) {
      return ProductType.of(targetNode.getConstructorArg().getXcodeProductType().get());
    } else if (targetNode.getConstructorArg().getExtension().isLeft()) {
      AppleBundleExtension extension = targetNode.getConstructorArg().getExtension().getLeft();

      boolean nodeIsAppleLibrary =
          ((Description<?>) binaryNode.getDescription()) instanceof AppleLibraryDescription;
      boolean nodeIsCxxLibrary =
          ((Description<?>) binaryNode.getDescription()) instanceof CxxLibraryDescription;
      if (nodeIsAppleLibrary || nodeIsCxxLibrary) {
        if (binaryNode.getBuildTarget().getFlavors().contains(
            CxxDescriptionEnhancer.SHARED_FLAVOR)) {
          Optional<ProductType> productType =
              dylibProductTypeByBundleExtension(extension);
          if (productType.isPresent()) {
            return productType.get();
          }
        } else if (extension == AppleBundleExtension.FRAMEWORK) {
          return ProductType.STATIC_FRAMEWORK;
        }
      } else if (
          binaryNode.getDescription() instanceof AppleBinaryDescription) {
        if (extension == AppleBundleExtension.APP) {
          return ProductType.APPLICATION;
        }
      } else if (
          binaryNode.getDescription() instanceof AppleTestDescription) {
        TargetNode<AppleTestDescription.Arg, ?> testNode =
            binaryNode.castArg(AppleTestDescription.Arg.class).get();
        if (testNode.getConstructorArg().isUiTest()) {
          return ProductType.UI_TEST;
        } else {
          return ProductType.UNIT_TEST;
        }
      }
    }

    return ProductType.BUNDLE;
  }

  private boolean shouldGenerateReadOnlyFiles() {
    return options.contains(Option.GENERATE_READ_ONLY_FILES);
  }

  private static String getExtensionString(Either<AppleBundleExtension, String> extension) {
    return extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
  }

  private static boolean isFrameworkBundle(HasAppleBundleFields arg) {
    return arg.getExtension().isLeft() &&
        arg.getExtension().getLeft().equals(AppleBundleExtension.FRAMEWORK);
  }

  private static boolean bundleRequiresRemovalOfAllTransitiveFrameworks(
      TargetNode<? extends HasAppleBundleFields, ?> targetNode) {
    return isFrameworkBundle(targetNode.getConstructorArg());
  }

  private static boolean bundleRequiresAllTransitiveFrameworks(
      TargetNode<? extends AppleNativeTargetDescriptionArg, ?> binaryNode) {
    return binaryNode.castArg(AppleBinaryDescription.Arg.class).isPresent();
  }

  private Path resolveSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return projectFilesystem.relativize(defaultPathResolver.getAbsolutePath(sourcePath));
    }
    Preconditions.checkArgument(sourcePath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath<?> buildTargetSourcePath = (BuildTargetSourcePath<?>) sourcePath;
    BuildTarget buildTarget = buildTargetSourcePath.getTarget();
    TargetNode<?, ?> node = targetGraph.get(buildTarget);
    Optional<TargetNode<ExportFileDescription.Arg, ?>> exportFileNode = node.castArg(
        ExportFileDescription.Arg.class);
    if (!exportFileNode.isPresent()) {
      BuildRuleResolver resolver = buildRuleResolverForNode.apply(node);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
      Path output = pathResolver.getAbsolutePath(sourcePath);
      if (output == null) {
        throw new HumanReadableException(
            "The target '%s' does not have an output.",
            node.getBuildTarget());
      }
      requiredBuildTargetsBuilder.add(buildTarget);
      return projectFilesystem.relativize(output);
    }

    Optional<SourcePath> src = exportFileNode.get().getConstructorArg().src;
    if (!src.isPresent()) {
      Path output = buildTarget.getCellPath()
        .resolve(buildTarget.getBasePath())
        .resolve(buildTarget.getShortNameAndFlavorPostfix());
      return projectFilesystem.relativize(output);
    }

    return resolveSourcePath(src.get());
  }

  private boolean isLibraryWithSourcesToCompile(TargetNode<?, ?> input) {
    if (input.getDescription() instanceof HalideLibraryDescription) {
      return true;
    }

    Optional<TargetNode<CxxLibraryDescription.Arg, ?>> library =
        getLibraryNode(targetGraph, input);
    if (!library.isPresent()) {
      return false;
    }
    return (library.get().getConstructorArg().srcs.size() != 0);
  }

  /**
   * @return product type of a bundle containing a dylib.
   */
  private static Optional<ProductType> dylibProductTypeByBundleExtension(
      AppleBundleExtension extension) {
    switch (extension) {
      case FRAMEWORK:
        return Optional.of(ProductType.FRAMEWORK);
      case APPEX:
        return Optional.of(ProductType.APP_EXTENSION);
      case BUNDLE:
        return Optional.of(ProductType.BUNDLE);
      case XCTEST:
        return Optional.of(ProductType.UNIT_TEST);
      // $CASES-OMITTED$
      default:
        return Optional.empty();
    }
  }

  /**
   * Determines if a target node is for watchOS2 application
   * @param targetNode A target node
   * @return If the given target node is for an watchOS2 application
   */
  private static boolean isWatchApplicationNode(TargetNode<?, ?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      return arg.getXcodeProductType().equals(
          Optional.of(ProductType.WATCH_APPLICATION.getIdentifier())
      );
    }
    return false;
  }

  private Path getPathToHeaderMapsRoot() {
    return projectFilesystem.getBuckPaths().getGenDir().resolve("_p");
  }

  private Path getPathToHeadersPath(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      String suffix) {
    String hashedPath = BaseEncoding.base64Url().omitPadding().encode(
      Hashing.sha1().hashString(
          targetNode.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName(),
          Charsets.UTF_8).asBytes()).substring(0, 10);
    return getPathToHeaderMapsRoot().resolve(hashedPath + suffix);
  }

  private Path getPathToHeaderSymlinkTree(
      TargetNode<? extends CxxLibraryDescription.Arg, ?> targetNode,
      HeaderVisibility headerVisibility) {
    return getPathToHeadersPath(
        targetNode, AppleHeaderVisibilities.getHeaderSymlinkTreeSuffix(headerVisibility));
  }

  private Path getPathToMergedHeaderMap() {
    return getPathToHeaderMapsRoot().resolve("pub-hmap");
  }

  /**
   * An expander for the location macro which leaves it as-is.
   */
  private static class AsIsLocationMacroExpander extends AbstractMacroExpander<LocationMacro> {

    @Override
    public Class<LocationMacro> getInputClass() {
      return LocationMacro.class;
    }

    @Override
    protected LocationMacro parse(
        BuildTarget target,
        CellPathResolver cellNames,
        ImmutableList<String> input)
        throws MacroException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String expandFrom(
        BuildTarget target,
        CellPathResolver cellNames,
        BuildRuleResolver resolver,
        LocationMacro input)
        throws MacroException {
      return String.format("$(location %s)", input.getTarget());
    }
  }

}
