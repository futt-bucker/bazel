// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.java.proto;

import static com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER;
import static com.google.devtools.build.lib.rules.java.proto.JplCcLinkParams.createCcLinkingInfo;
import static com.google.devtools.build.lib.rules.java.proto.StrictDepsUtils.constructJcapFromAspectDeps;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.java.JavaCcInfoProvider;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider;
import com.google.devtools.build.lib.rules.java.JavaSourceJarsProvider;
import javax.annotation.Nullable;

/** Implementation of the java_proto_library rule. */
public class JavaProtoLibrary implements RuleConfiguredTargetFactory {

  @Override
  @Nullable
  public ConfiguredTarget create(final RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {

    if (ruleContext.getFragment(JavaConfiguration.class).isDisallowStrictDepsForJpl()
        && ruleContext.attributes().has("strict_deps")
        && ruleContext.attributes().isAttributeValueExplicitlySpecified("strict_deps")) {
      ruleContext.attributeError("strict_deps", "The strict_deps attribute has been removed.");
      return null;
    }

    Iterable<JavaProtoLibraryAspectProvider> javaProtoLibraryAspectProviders =
        ruleContext.getPrerequisites("deps", JavaProtoLibraryAspectProvider.class);

    JavaCompilationArgsProvider dependencyArgsProviders =
        constructJcapFromAspectDeps(
            ruleContext, javaProtoLibraryAspectProviders, /* alwaysStrict= */ true);

    // We assume that the runtime jars will not have conflicting artifacts
    // with the same root relative path
    Runfiles runfiles =
        new Runfiles.Builder(ruleContext.getWorkspaceName())
            .addTransitiveArtifactsWrappedInStableOrder(dependencyArgsProviders.getRuntimeJars())
            .build();

    JavaSourceJarsProvider sourceJarsProvider =
        JavaSourceJarsProvider.merge(
            ruleContext.getPrerequisites("deps", JavaSourceJarsProvider.class));

    NestedSetBuilder<Artifact> filesToBuild = NestedSetBuilder.stableOrder();

    filesToBuild.addAll(sourceJarsProvider.getSourceJars());

    for (JavaProtoLibraryAspectProvider provider : javaProtoLibraryAspectProviders) {
      filesToBuild.addTransitive(provider.getJars());
    }

    JavaInfo.Builder javaInfoBuilder =
        JavaInfo.Builder.create()
            .addProvider(JavaCompilationArgsProvider.class, dependencyArgsProviders)
            .addProvider(JavaSourceJarsProvider.class, sourceJarsProvider)
            .addProvider(JavaRuleOutputJarsProvider.class, JavaRuleOutputJarsProvider.EMPTY);

    RuleConfiguredTargetBuilder result =
        new RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(filesToBuild.build())
            .addProvider(RunfilesProvider.simple(runfiles))
            .addOutputGroup(
                OutputGroupInfo.DEFAULT, NestedSetBuilder.<Artifact>emptySet(STABLE_ORDER));

    if (ruleContext.getFragment(JavaConfiguration.class).jplPropagateCcLinkParamsStore()) {
      javaInfoBuilder.addProvider(
          JavaCcInfoProvider.class, createCcLinkingInfo(ruleContext, ImmutableList.of()));
    }
    result.addNativeDeclaredProvider(javaInfoBuilder.build());

    return result.build();
  }
}
