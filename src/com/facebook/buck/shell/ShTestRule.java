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

package com.facebook.buck.shell;

import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.LabelsAttributeBuilder;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
public class ShTestRule extends DoNotUseAbstractBuildable implements TestRule {

  private final String test;
  private final ImmutableSet<String> labels;

  protected ShTestRule(
      BuildRuleParams buildRuleParams,
      String test,
      Set<String> labels) {
    super(buildRuleParams);
    this.test = Preconditions.checkNotNull(test);
    this.labels = ImmutableSet.copyOf(labels);
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("test", test);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.SH_TEST;
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return ImmutableSet.of(test);
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context) {
    // Nothing to build: test is run directly.
    return ImmutableList.of();
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    // If result.json was not written, then the test needs to be run.
    ProjectFilesystem filesystem = executionContext.getProjectFilesystem();
    return filesystem.isFile(getPathToTestOutputResult());
  }

  @Override
  public List<Step> runTests(BuildContext buildContext, ExecutionContext executionContext) {
    Preconditions.checkState(isRuleBuilt(), "%s must be built before tests can be run.", this);

    Step mkdirClean = new MakeCleanDirectoryStep(getPathToTestOutputDirectory());

    // Return a single command that runs an .sh file with no arguments.
    Step runTest = new RunShTestAndRecordResultStep(test, getPathToTestOutputResult());

    return ImmutableList.of(mkdirClean, runTest);
  }

  private String getPathToTestOutputDirectory() {
    return String.format("%s/%s/__sh_test_%s_output__",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePath(),
        getBuildTarget().getShortName());
  }

  @VisibleForTesting
  String getPathToTestOutputResult() {
    return getPathToTestOutputDirectory() + "/result.json";
  }

  @Override
  public Callable<TestResults> interpretTestResults(ExecutionContext context) {
    return new Callable<TestResults>() {

      @Override
      public TestResults call() throws Exception {
        File resultsFile = new File(getPathToTestOutputResult());
        ObjectMapper mapper = new ObjectMapper();
        TestResultSummary testResultSummary = mapper.readValue(resultsFile,
            TestResultSummary.class);
        TestCaseSummary testCaseSummary = new TestCaseSummary(
            getFullyQualifiedName(), ImmutableList.of(testResultSummary));
        return new TestResults(ImmutableList.of(testCaseSummary));
      }

    };
  }

  public static Builder newShTestRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<ShTestRule> implements
      LabelsAttributeBuilder {

    private String test;
    private ImmutableSet<String> labels = ImmutableSet.of();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public ShTestRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new ShTestRule(buildRuleParams, test, labels);
    }

    public Builder setTest(String test) {
      this.test = test;
      return this;
    }

    @Override
    public Builder setLabels(ImmutableSet<String> labels) {
      this.labels = labels;
      return this;
    }
  }
}
