package dev.multiclone.engine

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ValueType
import dev.multiclone.core.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry

class ApkTransformer : CloneBuilder {
    override fun build(source: ApkSource, plan: ClonePlan, outputDirectory: File, progress: (String) -> Unit): List<Pair<File, String?>> {
        require(source.buildable) { "This source has blocking compatibility findings." }
        require(plan.engineVersion == 1) { "Unsupported transformation version." }
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory)
        val map = plan.identityMap.ifEmpty { identityMap(source, plan.packageName) }
        val providerNames = source.parts.flatMap { part ->
            ApkModule.loadApkFile(part.file).use { apk ->
                apk.androidManifest.manifestElement.elements().filter { it.name == "provider" }
                    .flatMap { it.text("authorities")?.split(';').orEmpty() }
            }
        }.toSet()
        return source.parts.mapIndexed { index, part ->
            check(!Thread.currentThread().isInterrupted) { "Cancelled." }
            require(part.file.sha256() == part.sha256) { "Source files changed. Import and analyze them again." }
            progress("Transforming ${part.splitName ?: "base"} (${index + 1}/${source.parts.size})")
            val output = File(outputDirectory, if (part.splitName == null) "base-unsigned.apk" else "split-$index-unsigned.apk")
            ApkModule.loadApkFile(part.file).use { apk ->
                val manifest = apk.androidManifest
                val root = manifest.manifestElement
                for (node in root.elements() + root) {
                    for (name in CLASS_ATTRIBUTES[node.name].orEmpty()) {
                        node.android(name)?.let { attr -> attr.valueAsString?.takeIf { it.isNotBlank() }?.let { attr.setValueAsString(fullClass(source.packageName, it)) } }
                    }
                    for (name in listOf("authorities", "permission", "readPermission", "writePermission", "taskAffinity", "process")) {
                        node.android(name)?.let { a ->
                            a.valueAsString?.let { value ->
                                val replaced = value.split(';').joinToString(";") { map[it] ?: it }
                                if (value != replaced) a.setValueAsString(replaced)
                            }
                        }
                    }
                    if (node.name in setOf("permission", "permission-tree", "permission-group", "uses-permission", "uses-permission-sdk-23", "action")) {
                        node.android("name")?.let { a -> map[a.valueAsString]?.let { a.setValueAsString(it) } }
                    }
                    node.android("permissionGroup")?.let { a -> map[a.valueAsString]?.let { a.setValueAsString(it) } }
                }
                manifest.setPackageName(plan.packageName)
                if ((manifest.minSdkVersion ?: 1) < 28) manifest.setMinSdkVersion(28)
                if (apk.hasTableBlock()) {
                    for (pkg in apk.tableBlock.packageArray.listItems()) {
                        if (pkg.name == source.packageName) pkg.name = plan.packageName
                        val libraries = pkg.libraryInfo
                        while (libraries.hasNext()) libraries.next().let { library ->
                            if (library.name == source.packageName) library.name = plan.packageName
                        }
                    }
                }
                if (part.splitName == null) customize(apk, plan)
                DexIdentityRewriter.rewrite(apk, map.filterKeys { it in providerNames })
                // Strip only signing metadata, preserving META-INF services and unrelated assets.
                apk.inputSources.filter { isSignatureMetadata(it.alias) }.forEach { apk.removeInputSource(it.alias) }
                apk.setApkSignatureBlock(null)
                apk.refreshManifest()
                if (apk.hasTableBlock()) apk.tableBlock.refresh()
                apk.createApkFileWriter(output).apply {
                    setZipAligner(ZipAligner().apply {
                        setDefaultAlignment(4)
                        setFileAlignment(ZipAligner.PREDICATE_NATIVE_LIBS, 16384)
                    })
                }.write()
            }
            ArchiveGuard.check(output)
            ApkModule.loadApkFile(output).use { require(it.packageName == plan.packageName) { "Output package verification failed." } }
            output to part.splitName
        }
    }

    fun identityMap(source: ApkSource, target: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (part in source.parts) ApkModule.loadApkFile(part.file).use { apk ->
            val nodes = apk.androidManifest.manifestElement.elements()
            for (node in nodes) {
                if (node.name == "provider") node.text("authorities")?.split(';')?.forEach { original ->
                    map[original] = if (original == source.packageName || original.startsWith(source.packageName + "."))
                        target + original.removePrefix(source.packageName)
                    else "$target.provider.p${shortHash(original)}"
                }
                if (node.name in setOf("permission", "permission-tree", "permission-group")) node.text("name")?.let {
                    map[it] = if (it.startsWith(source.packageName + ".")) target + it.removePrefix(source.packageName) else "$target.permission.p${shortHash(it)}"
                }
                for (field in listOf("process", "taskAffinity")) node.text(field)?.let {
                    if (it == source.packageName || it.startsWith(source.packageName + ".")) map[it] = target + it.removePrefix(source.packageName)
                }
            }
        }
        map[source.packageName] = target
        return map
    }

    private fun customize(apk: ApkModule, plan: ClonePlan) {
        val manifest = apk.androidManifest
        manifest.setApplicationLabel(plan.label)
        val launchers = manifest.manifestElement.elements().filter { node ->
            node.name in setOf("activity", "activity-alias") && node.elements().any { it.name == "category" && it.text("name") == "android.intent.category.LAUNCHER" }
        }
        launchers.forEach { it.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString(plan.label) }
        val pkg = if (apk.hasTableBlock()) apk.tableBlock.packageArray.listItems().firstOrNull { it.name == plan.packageName } else null
        pkg?.getOrCreate("", "string", "multiclone_custom_label")?.let { label ->
            label.setValueAsString(plan.label)
            manifest.setApplicationLabel(label.resourceId)
            launchers.forEach { it.getOrCreateAndroidAttribute("label", 0x01010001).apply { valueType = ValueType.REFERENCE; data = label.resourceId } }
        }
        val icon = plan.icon ?: return
        require(icon.isFile && icon.length() <= 2 * 1024 * 1024) { "Invalid icon file." }
        require(apk.hasTableBlock()) { "Custom icons currently require a resource table." }
        requireNotNull(pkg) { "The app's resource package could not be found." }
        val path = "res/drawable/multiclone_custom_icon.png"
        val iconEntry = pkg.getOrCreate("", "drawable", "multiclone_custom_icon")
        iconEntry.setValueAsString(path)
        apk.add(ByteInputSource(icon.readBytes(), path))
        val background = pkg.getOrCreate("", "color", "multiclone_icon_background").apply { setValueAsRaw(ValueType.COLOR_ARGB8, 0xff102a43.toInt()) }
        val adaptivePath = "res/mipmap-anydpi-v26/multiclone_custom_icon.xml"
        val adaptive = ResXmlDocument().apply {
            setApkFile(apk); setPackageBlock(pkg)
            val root = newElement("adaptive-icon")
            root.newElement("background").getOrCreateAndroidAttribute("drawable", 0x01010199).apply { valueType = ValueType.REFERENCE; data = background.resourceId }
            root.newElement("foreground").getOrCreateAndroidAttribute("drawable", 0x01010199).apply { valueType = ValueType.REFERENCE; data = iconEntry.resourceId }
            refreshFull()
        }
        val defaultIcon = pkg.getOrCreate("", "mipmap", "multiclone_custom_icon").apply { setValueAsString(path) }
        pkg.getOrCreate("anydpi-v26", "mipmap", "multiclone_custom_icon").setValueAsString(adaptivePath)
        apk.add(ByteInputSource(adaptive.bytes, adaptivePath))
        manifest.iconResourceId = defaultIcon.resourceId
        manifest.roundIconResourceId = defaultIcon.resourceId
        launchers.forEach {
            it.getOrCreateAndroidAttribute("icon", 0x01010002).apply { valueType = ValueType.REFERENCE; data = defaultIcon.resourceId }
            it.android("roundIcon")?.apply { valueType = ValueType.REFERENCE; data = defaultIcon.resourceId }
        }
    }
    private fun shortHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun isSignatureMetadata(name: String): Boolean = name.matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)", RegexOption.IGNORE_CASE)) || name.equals("META-INF/MANIFEST.MF", true)
    private companion object {
        val CLASS_ATTRIBUTES = mapOf(
            "application" to listOf("name", "backupAgent", "appComponentFactory", "manageSpaceActivity", "zygotePreloadName"),
            "activity" to listOf("name", "parentActivityName"), "activity-alias" to listOf("name", "targetActivity", "parentActivityName"),
            "service" to listOf("name"), "receiver" to listOf("name"), "provider" to listOf("name"),
        )
    }
}
