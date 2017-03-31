/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjProjectConfig {

  public abstract JavaBuckConfig getJavaBuckConfig();

  protected abstract BuckConfig getBuckConfig();

  @Value.Default
  public boolean isAutogenerateAndroidFacetSourcesEnabled() {
    return true;
  }

  public abstract Optional<String> getProjectJdkName();

  public abstract Optional<String> getProjectJdkType();

  public abstract Optional<String> getAndroidModuleSdkName();

  public abstract Optional<String> getAndroidModuleSdkType();

  public abstract Optional<String> getIntellijModuleSdkName();

  public abstract ImmutableSet<String> getIntellijSdkTargets();

  public abstract Optional<String> getJavaModuleSdkName();

  public abstract Optional<String> getJavaModuleSdkType();

  public abstract Optional<String> getProjectLanguageLevel();

  public abstract List<String> getExcludedResourcePaths();

  public abstract ImmutableMap<String, String> getDepToGeneratedSourcesMap();

  public abstract Optional<Path> getAndroidManifest();

}
