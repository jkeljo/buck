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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.SingletonArtifactCacheFactory;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.ide.intellij.IjAndroidHelper;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CoercedTypeCache;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMockSupport;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Unit test for {@link CleanCommand}.
 */
public class CleanCommandTest extends EasyMockSupport {

  private ProjectFilesystem projectFilesystem;

  // TODO(mbolin): When it is possible to inject a mock object for stderr,
  // create a test that runs `buck clean unexpectedarg` and verify that the
  // exit code is 1 and that the appropriate error message is printed.

  @Test
  public void testCleanCommandNoArguments()
      throws CmdLineException, IOException, InterruptedException {
    CommandRunnerParams params = createCommandRunnerParams();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());

    // Simulate `buck clean`.
    CleanCommand cleanCommand = createCommandFromArgs();
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
  }

  @Test
  public void testCleanCommandWithProjectArgument()
      throws CmdLineException, IOException, InterruptedException {
    CommandRunnerParams params = createCommandRunnerParams();

    // Set up mocks.
    projectFilesystem.mkdirs(IjAndroidHelper.getAndroidGenPath(projectFilesystem));
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getAnnotationDir());

    // Simulate `buck clean --project`.
    CleanCommand cleanCommand = createCommandFromArgs("--project");
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);

    assertFalse(projectFilesystem.exists(IjAndroidHelper.getAndroidGenPath(projectFilesystem)));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getAnnotationDir()));
  }

  private CleanCommand createCommandFromArgs(String... args) throws CmdLineException {
    CleanCommand command = new CleanCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  private CommandRunnerParams createCommandRunnerParams() throws InterruptedException, IOException {
    projectFilesystem = new FakeProjectFilesystem();

    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
        AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    return CommandRunnerParams.builder()
        .setConsole(new TestConsole())
        .setStdIn(new ByteArrayInputStream("".getBytes("UTF-8")))
        .setCell(cell)
        .setAndroidPlatformTargetSupplier(androidPlatformTargetSupplier)
        .setArtifactCacheFactory(new SingletonArtifactCacheFactory(new NoopArtifactCache()))
        .setBuckEventBus(BuckEventBusFactory.newInstance())
        .setCoercedTypeCache(new CoercedTypeCache(new DefaultTypeCoercerFactory(objectMapper)))
        .setParser(createMock(Parser.class))
        .setPlatform(Platform.detect())
        .setEnvironment(ImmutableMap.copyOf(System.getenv()))
        .setJavaPackageFinder(new FakeJavaPackageFinder())
        .setObjectMapper(objectMapper)
        .setClock(new DefaultClock())
        .setProcessManager(Optional.empty())
        .setWebServer(Optional.empty())
        .setBuckConfig(FakeBuckConfig.builder().build())
        .setFileHashCache(new StackedFileHashCache(ImmutableList.of()))
        .setExecutors(ImmutableMap.of())
        .setBuildEnvironmentDescription(
            CommandRunnerParamsForTesting.BUILD_ENVIRONMENT_DESCRIPTION)
        .setVersionedTargetGraphCache(new VersionedTargetGraphCache())
        .setActionGraphCache(new ActionGraphCache(new BroadcastEventListener()))
        .setKnownBuildRuleTypesFactory(
            new KnownBuildRuleTypesFactory(
                new FakeProcessExecutor(),
                new FakeAndroidDirectoryResolver()))
        .build();
  }

}
