package app.tubehaus.patch.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val PKG = FqName("app.tubehaus.patch")
private val PATCHABLE = FqName("app.tubehaus.patch.Patchable")

/** Registered through META-INF/services: the compiler calls it for every module it builds. */
class PatchRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(PatchExtension())
    }
}

/**
 * At the start of every function marked @Patchable it puts:
 *
 *     val r = TubePatch.call("pkg.Class.name", "kotlin.Int", arrayOf(arg1, arg2, ...))
 *     if (r !== NotPatched) return r as Int
 *
 * so a patch that has a replacement for the function runs instead of it; with none, the
 * function goes on as compiled. (Kotlin calls functions directly, so unlike Objective-C there
 * is no method table to swap at run time: the check has to be compiled in.)
 */
class PatchExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val patch: IrClassSymbol = pluginContext.referenceClass(ClassId(PKG, Name.identifier("TubePatch"))) ?: return
        val none: IrClassSymbol = pluginContext.referenceClass(ClassId(PKG, Name.identifier("NotPatched"))) ?: return
        val call: IrSimpleFunctionSymbol = pluginContext.referenceFunctions(
            CallableId(PKG, FqName("TubePatch"), Name.identifier("call")),
        ).singleOrNull() ?: return

        moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
                if (declaration.hasAnnotation(PATCHABLE) && declaration.body is IrBlockBody) {
                    inject(pluginContext, declaration, patch, none, call)
                }
                return super.visitSimpleFunction(declaration)
            }
        })
    }

    private fun inject(ctx: IrPluginContext, fn: IrSimpleFunction, patch: IrClassSymbol, none: IrClassSymbol, call: IrSimpleFunctionSymbol) {
        val anyN = ctx.irBuiltIns.anyNType
        val params = fn.parameters.filter { it.kind == IrParameterKind.Regular }
        val returns = fn.returnType
        val id = fn.kotlinFqName.asString()
        val b = DeclarationIrBuilder(ctx, fn.symbol, fn.startOffset, fn.endOffset)
        val head = b.irBlockBody {
            val args = IrVarargImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET, ctx.irBuiltIns.arrayClass.typeWith(anyN), anyN, params.map { irGet(it) },
            )
            val arrayOf = irCall(ctx.irBuiltIns.arrayOf).apply {
                typeArguments[0] = anyN
                arguments[0] = args
            }
            val result = irTemporary(irCall(call).apply {
                arguments[0] = irGetObject(patch)
                arguments[1] = irString(id)
                arguments[2] = irString(returns.classFqName?.asString() ?: "kotlin.Any")
                arguments[3] = arrayOf
            })
            +irIfThen(
                ctx.irBuiltIns.unitType,
                irCall(ctx.irBuiltIns.booleanNotSymbol).apply { arguments[0] = irEqeqeq(irGet(result), irGetObject(none)) },
                irReturn(if (returns.isUnit()) irGetObject(ctx.irBuiltIns.unitClass) else irAs(irGet(result), returns)),
            )
        }
        (fn.body as IrBlockBody).statements.addAll(0, head.statements)
    }
}
