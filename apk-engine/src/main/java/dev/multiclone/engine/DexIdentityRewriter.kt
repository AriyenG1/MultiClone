package dev.multiclone.engine

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.rewriter.*
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.BufferedInputStream
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/** Only recognizes a terminal const-string → Uri.parse → move-result → return sequence.
 * Class descriptors, reflection strings, auth URLs and arbitrary package constants are untouched.
 */
internal object DexIdentityRewriter {
    fun rewrite(apk: ApkModule, providerMap: Map<String, String>) {
        for (input in apk.listDexFiles()) {
            val dex = input.openStream().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(it)) }
            if (dex.stringReferences.none { ref ->
                    val value = ref.string
                    value.startsWith("content://") && value.removePrefix("content://").substringBefore('/').substringBefore('?').substringBefore('#') in providerMap
                }) continue
            var changes = 0
            val rewriter = DexRewriter(object : RewriterModule() {
                override fun getMethodImplementationRewriter(rewriters: Rewriters): Rewriter<MethodImplementation> = Rewriter { implementation ->
                val instructions = implementation.instructions.toList()
                fun unchanged(): MethodImplementation = implementation
                run rewrite@ {
                if (instructions.size < 4) return@rewrite unchanged()
                val sequence = instructions.takeLast(4)
                val constant = sequence[0]
                if (constant.opcode !in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)) return@rewrite unchanged()
                val old = ((constant as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@rewrite unchanged()
                if (!old.startsWith("content://")) return@rewrite unchanged()
                val authority = old.removePrefix("content://").substringBefore('/').substringBefore('?').substringBefore('#')
                val target = providerMap[authority] ?: return@rewrite unchanged()
                val invocation = sequence[1]
                val reference = (invocation as? ReferenceInstruction)?.reference as? MethodReference ?: return@rewrite unchanged()
                val registers = invocation as? FiveRegisterInstruction ?: return@rewrite unchanged()
                if (invocation.opcode != Opcode.INVOKE_STATIC || reference.definingClass != "Landroid/net/Uri;" ||
                    reference.name != "parse" || reference.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;") ||
                    registers.registerCount != 1 || registers.registerC != (constant as OneRegisterInstruction).registerA) return@rewrite unchanged()
                if (sequence[2].opcode != Opcode.MOVE_RESULT_OBJECT || sequence[3].opcode != Opcode.RETURN_OBJECT ||
                    (sequence[2] as OneRegisterInstruction).registerA != (sequence[3] as OneRegisterInstruction).registerA) return@rewrite unchanged()
                // Reject branch targets into the sequence: only straight-line methods are supported.
                if (instructions.any { it.opcode.toString().startsWith("IF_") || it.opcode.toString().startsWith("GOTO") ||
                        it.opcode in setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH) } || implementation.tryBlocks.isNotEmpty()) return@rewrite unchanged()
                val text = "content://$target" + old.removePrefix("content://$authority")
                val register = (constant as OneRegisterInstruction).registerA
                val replacement = if (constant.opcode == Opcode.CONST_STRING)
                    ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(text))
                else ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register, ImmutableStringReference(text))
                changes++
                ImmutableMethodImplementation(implementation.registerCount,
                    instructions.dropLast(4) + listOf(replacement) + sequence.drop(1), implementation.tryBlocks, implementation.debugItems)
                }
                }
            })
            val store = MemoryDataStore()
            try {
                DexPool.writeTo(store, rewriter.dexFileRewriter.rewrite(dex))
                if (changes > 0) apk.add(ByteInputSource(store.data, input.alias))
            } finally { store.close() }
        }
    }
}
