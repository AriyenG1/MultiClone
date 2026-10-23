package dev.multiclone.app

import dev.multiclone.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object JsonCodec {
    fun source(source: ApkSource): String = JSONObject().apply {
        put("id", source.id); put("package", source.packageName); put("label", source.label); put("version", source.versionCode)
        put("parts", JSONArray().apply { source.parts.forEach { part -> put(JSONObject().apply {
            put("file", part.file.absolutePath); put("split", part.splitName ?: JSONObject.NULL); put("sha256", part.sha256)
            put("signers", JSONArray(part.signerDigests.toList())); put("requires", JSONArray(part.requiredSplits.toList()))
        }) } })
        put("findings", JSONArray().apply { source.findings.forEach { put(JSONObject().put("code", it.code).put("severity", it.severity.name).put("message", it.message)) } })
    }.toString()
    fun source(text: String): ApkSource = JSONObject(text).let { root ->
        ApkSource(root.getString("id"), root.getString("package"), root.getString("label"), root.getLong("version"),
            root.getJSONArray("parts").objects().map { part -> ApkPart(File(part.getString("file")), part.nullable("split"), part.getString("sha256"),
                root.getString("package"), root.getLong("version"), part.getJSONArray("signers").strings().toSet(), part.getJSONArray("requires").strings().toSet()) },
            root.getJSONArray("findings").objects().map { Finding(it.getString("code"), Severity.valueOf(it.getString("severity")), it.getString("message")) })
    }
    fun plan(plan: ClonePlan): String = JSONObject().apply {
        put("id", plan.id); put("package", plan.packageName); put("label", plan.label); put("icon", plan.icon?.absolutePath ?: JSONObject.NULL)
        put("map", JSONObject(plan.identityMap)); put("engine", plan.engineVersion)
    }.toString()
    fun plan(text: String): ClonePlan = JSONObject(text).let { root ->
        val map = root.getJSONObject("map")
        ClonePlan(root.getString("id"), root.getString("package"), root.getString("label"), root.nullable("icon")?.let(::File),
            map.keys().asSequence().associateWith { map.getString(it) }, root.getInt("engine"))
    }
    fun artifacts(files: List<SignedArtifact>) = JSONArray().apply { files.forEach { put(JSONObject().put("file", it.file.absolutePath).put("split", it.splitName ?: JSONObject.NULL).put("sha256", it.sha256)) } }.toString()
    fun artifacts(text: String): List<SignedArtifact> = JSONArray(text).objects().map { SignedArtifact(File(it.getString("file")), it.nullable("split"), it.getString("sha256")) }
    fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    fun JSONArray.strings() = (0 until length()).map { getString(it) }
    fun JSONObject.nullable(name: String) = if (isNull(name)) null else getString(name)
}
