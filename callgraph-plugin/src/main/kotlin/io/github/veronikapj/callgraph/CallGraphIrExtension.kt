package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

class CallGraphIrExtension(private val outputPath: String) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val db = CallGraphDb(outputPath)
        try {
            moduleFragment.accept(CallGraphVisitor(db), null)
        } finally {
            db.close()
        }
    }
}

class CallGraphVisitor(private val db: CallGraphDb) : IrElementVisitorVoid {
    private val functionStack = ArrayDeque<IrFunction>()

    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

    override fun visitFunction(declaration: IrFunction) {
        functionStack.addLast(declaration)
        super.visitFunction(declaration)
        functionStack.removeLast()
    }

    override fun visitCall(expression: IrCall) {
        val caller = functionStack.lastOrNull() ?: run { super.visitCall(expression); return }
        val callerFqn = caller.kotlinFqName?.asString() ?: run { super.visitCall(expression); return }
        val calleeFqn = runCatching { expression.symbol.owner.kotlinFqName?.asString() }
            .getOrNull() ?: run { super.visitCall(expression); return }

        if (!calleeFqn.startsWith("kotlin.") && !calleeFqn.startsWith("java.")) {
            val callerFile = runCatching {
                var p: Any? = caller.parent
                while (p != null && p !is IrFile) p = (p as? IrDeclaration)?.parent
                (p as? IrFile)?.fileEntry?.name ?: ""
            }.getOrDefault("")
            db.upsertEdge(callerFqn, calleeFqn, callerFile)
        }
        super.visitCall(expression)
    }
}
