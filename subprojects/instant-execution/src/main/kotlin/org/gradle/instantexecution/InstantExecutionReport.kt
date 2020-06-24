/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution

import groovy.json.JsonOutput
import org.gradle.api.internal.DocumentationRegistry

import org.gradle.api.logging.Logging

import org.gradle.instantexecution.initialization.InstantExecutionStartParameter
import org.gradle.instantexecution.problems.PropertyKind
import org.gradle.instantexecution.problems.PropertyProblem
import org.gradle.instantexecution.problems.PropertyTrace
import org.gradle.instantexecution.problems.buildConsoleSummary
import org.gradle.instantexecution.problems.firstTypeFrom
import org.gradle.instantexecution.problems.taskPathFrom

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL


class InstantExecutionReport(

    private
    val startParameter: InstantExecutionStartParameter,

    private
    val cacheKey: InstantExecutionCacheKey

) {

    companion object {

        private
        val logger = Logging.getLogger(InstantExecutionReport::class.java)

        private
        const val reportHtmlFileName = "configuration-cache-report.html"
    }

    private
    val outputDirectory: File by lazy {
        startParameter.rootDirectory.resolve(
            "build/reports/configuration-cache/$cacheKey"
        ).let { base ->
            if (!base.exists()) base
            else generateSequence(1) { it + 1 }
                .map { base.resolveSibling("${base.name}-$it") }
                .first { !it.exists() }
        }
    }

    internal
    val htmlReportFile: File
        get() = outputDirectory.resolve(reportHtmlFileName)

    internal
    fun logConsoleSummary(cacheAction: String, problems: List<PropertyProblem>) {
        logger.warn(buildConsoleSummary(cacheAction, problems, htmlReportFile))
    }

    internal
    fun writeReportFiles(cacheAction: String, problems: List<PropertyProblem>) {
        require(outputDirectory.mkdirs()) {
            "Could not create configuration cache report directory '$outputDirectory'"
        }
        writeReportFile(cacheAction, problems, outputDirectory)
    }

    /**
     * Writes the report file to [outputDirectory].
     *
     * The file is laid out in such a way as to allow extracting the pure JSON model,
     * see [writeJsReportData].
     */
    private
    fun writeReportFile(cacheAction: String, problems: List<PropertyProblem>, outputDirectory: File) {
        val html = javaClass.requireResource(reportHtmlFileName)
        outputDirectory.resolve(reportHtmlFileName).bufferedWriter().use { writer ->
            html.openStream().bufferedReader().use { reader ->
                writer.writeReportFileText(reader, cacheAction, problems)
            }
        }
    }

    private
    fun BufferedWriter.writeReportFileText(htmlReader: BufferedReader, cacheAction: String, problems: List<PropertyProblem>) {
        var dataWritten = false
        htmlReader.forEachLine { line ->
            if (!dataWritten && line.contains("configuration-cache-report-data.js")) {
                appendLine("""<script type="text/javascript">""")
                writeJsReportData(cacheAction, problems)
                appendLine("</script>")
                dataWritten = true
            } else {
                appendLine(line)
            }
        }
        require(dataWritten) { "Didn't write report data, placeholder not found!" }
    }

    /**
     * Writes the report data function.
     *
     * The text is laid out in such a way as to allow extracting the pure JSON model
     * by looking for `// begin-report-data` and `// end-report-data`.
     */
    private
    fun BufferedWriter.writeJsReportData(cacheAction: String, problems: List<PropertyProblem>) {
        appendLine("function configurationCacheProblems() { return (")
        appendLine("// begin-report-data")
        writeJsonModelFor(cacheAction, problems)
        appendLine("// end-report-data")
        appendLine(");}")
    }

    private
    fun BufferedWriter.writeJsonModelFor(cacheAction: String, problems: List<PropertyProblem>) {
        val documentationRegistry = DocumentationRegistry()
        appendLine("{") // begin JSON
        appendLine("\"cacheAction\": \"$cacheAction\",")
        appendLine("\"documentationLink\": \"${documentationRegistry.getDocumentationFor("configuration_cache")}\",")
        appendLine("\"problems\": [") // begin problems
        problems.forEachIndexed { index, problem ->
            if (index > 0) append(',')
            append(
                JsonOutput.toJson(
                    mapOf(
                        "trace" to traceListOf(problem),
                        "message" to problem.message.fragments,
                        "documentationLink" to problem.documentationSection?.let { documentationRegistry.getDocumentationFor("configuration_cache", it) },
                        "error" to stackTraceStringOf(problem)
                    )
                )
            )
        }
        appendLine("]") // end problems
        appendLine("}") // end JSON
    }

    private
    fun Class<*>.requireResource(path: String): URL = getResource(path).also {
        require(it != null) { "Resource `$path` could not be found!" }
    }

    private
    fun stackTraceStringOf(problem: PropertyProblem): String? =
        problem.exception?.let {
            stackTraceStringFor(it)
        }

    private
    fun stackTraceStringFor(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    private
    fun traceListOf(problem: PropertyProblem): List<Map<String, Any>> =
        problem.trace.sequence.map(::traceToMap).toList()

    private
    fun traceToMap(trace: PropertyTrace): Map<String, Any> = when (trace) {
        is PropertyTrace.Property -> {
            when (trace.kind) {
                PropertyKind.Field -> mapOf(
                    "kind" to trace.kind.name,
                    "name" to trace.name,
                    "declaringType" to firstTypeFrom(trace.trace).name
                )
                else -> mapOf(
                    "kind" to trace.kind.name,
                    "name" to trace.name,
                    "task" to taskPathFrom(trace.trace)
                )
            }
        }
        is PropertyTrace.Task -> mapOf(
            "kind" to "Task",
            "path" to trace.path,
            "type" to trace.type.name
        )
        is PropertyTrace.Bean -> mapOf(
            "kind" to "Bean",
            "type" to trace.type.name
        )
        is PropertyTrace.BuildLogic -> mapOf(
            "kind" to "BuildLogic",
            "location" to trace.displayName.displayName
        )
        is PropertyTrace.BuildLogicClass -> mapOf(
            "kind" to "Class",
            "type" to trace.name
        )
        PropertyTrace.Gradle -> mapOf(
            "kind" to "Gradle"
        )
        PropertyTrace.Unknown -> mapOf(
            "kind" to "Unknown"
        )
    }
}
