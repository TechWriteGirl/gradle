/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.ide.visualstudio.fixtures;

import org.gradle.integtests.fixtures.executer.ExecutionFailure;
import org.gradle.integtests.fixtures.executer.ExecutionResult;
import org.gradle.integtests.fixtures.executer.OutputScrapingExecutionFailure;
import org.gradle.integtests.fixtures.executer.OutputScrapingExecutionResult;
import org.gradle.test.fixtures.file.ExecOutput;
import org.gradle.test.fixtures.file.TestFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.gradle.ide.fixtures.IdeCommandLineUtil.buildEnvironment;
import static org.testng.Assert.assertTrue;

public class MSBuildExecutor {
    public enum MSBuildAction {
        BUILD,
        CLEAN;

        @Override
        public String toString() {
            return this.name().toLowerCase();
        }
    }

    private final List<String> args = new ArrayList<String>();
    private final TestFile testDirectory;

    public MSBuildExecutor(TestFile testDirectory) {
        this.testDirectory = testDirectory;
    }

    public MSBuildExecutor withSolution(SolutionFile visualStudioSolution) {
        TestFile solutionFile = new TestFile(visualStudioSolution.getFile());
        solutionFile.assertIsFile();
        return addArguments(solutionFile.getAbsolutePath());
    }

    public MSBuildExecutor withConfiguration(String configurationName) {
        return addArguments("/p:Configuration=" + configurationName);
    }

    public MSBuildExecutor withArgument(String arg) {
        this.args.add(arg);
        return this;
    }

    private MSBuildExecutor addArguments(String... args) {
        this.args.addAll(Arrays.asList(args));
        return this;
    }

    public ExecutionResult succeeds() {
        return succeeds(MSBuildAction.BUILD);
    }

    public ExecutionResult succeeds(MSBuildAction action) {
        withArgument("/t:" + action.toString());
        ExecOutput result = findMSBuild().execute(args, buildEnvironment(testDirectory));
        System.out.println(result.getOut());
        return new OutputScrapingExecutionResult(trimLines(result.getOut()), trimLines(result.getError()));
    }

    public ExecutionFailure fails() {
        return fails(MSBuildAction.BUILD);
    }

    public ExecutionFailure fails(MSBuildAction action) {
        withArgument(action.toString());
        ExecOutput result = findMSBuild().execWithFailure(args, buildEnvironment(testDirectory));
        System.out.println(result.getOut());
        System.out.println(result.getError());
        return new OutputScrapingExecutionFailure(trimLines(result.getOut()), trimLines(result.getError()));
    }

    private String trimLines(String s) {
        return s.replaceAll("\r?\n\\s+", "\n");
    }

    private TestFile findMSBuild() {
        TestFile vswhere = new TestFile(System.getenv("ProgramFiles(x86)") + "/Microsoft Visual Studio/Installer/vswhere.exe");
        assertTrue(vswhere.exists(), "This test requires vswhere to be installed in '%ProgramFiles(x86)%/Microsoft Visual Studio/Installer/vswhere.exe'");

        TestFile installDir = new TestFile(vswhere.exec("-latest", "-products", "*", "-requires", "Microsoft.Component.MSBuild", "-property", "installationPath").getOut().trim());

        TestFile msbuild = installDir.file("MSBuild/15.0/Bin/MSBuild.exe");
        assertTrue(msbuild.exists(), "This test requires msbuild to be installed");
        return msbuild;
    }
}
