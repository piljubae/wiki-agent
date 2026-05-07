package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val OUTPUT_PATH_KEY = CompilerConfigurationKey.create<String>("callgraph.outputPath")

@OptIn(ExperimentalCompilerApi::class)
class CallGraphPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val outputPath = configuration[OUTPUT_PATH_KEY] ?: return
        IrGenerationExtension.registerExtension(CallGraphIrExtension(outputPath))
    }
}
