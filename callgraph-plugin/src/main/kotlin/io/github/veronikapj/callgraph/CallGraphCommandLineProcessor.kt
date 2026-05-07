package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class CallGraphCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "io.github.veronikapj.callgraph"
    override val pluginOptions = listOf(
        CliOption("outputPath", "<path>", "call_graph.db 출력 경로", required = true)
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        if (option.optionName == "outputPath") configuration.put(OUTPUT_PATH_KEY, value)
    }
}
