package dev.multiclone.app

import android.util.Base64
import dev.multiclone.signing.BackupCipher
import dev.multiclone.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackupManager(private val app: MultiCloneApplication) {
    suspend fun export(password: CharArray): ByteArray {
        val clones = app.dao.clones().filter { it.state != "BUILDING" && (it.artifacts != "[]" || it.state == "RECOVERED") }
        val sourceIds = clones.map { it.sourceId }.toSet()
        val root = JSONObject().put("format", 1)
        root.put("sources", JSONArray().apply {
            app.dao.sources().filter { it.id in sourceIds }.forEach { row ->
                val json = JSONObject(row.json)
                // Source binaries and filesystem locations are never exported.
                val parts = json.getJSONArray("parts")
                for (i in 0 until parts.length()) parts.getJSONObject(i).put("file", "")
                put(json)
            }
        })
        root.put("clones", JSONArray().apply { clones.forEach { row ->
            val plan = JSONObject(row.plan).put("icon", JSONObject.NULL)
            val key = app.vault.exportRaw(row.id)
            try { put(JSONObject().put("id", row.id).put("sourceId", row.sourceId).put("plan", plan)
                .put("version", row.versionCode).put("key", Base64.encodeToString(key, Base64.NO_WRAP))) }
            finally { key.fill(0) }
        } })
        val bytes = root.toString().toByteArray()
        return try { BackupCipher.encrypt(bytes, password) } finally { bytes.fill(0) }
    }
    suspend fun restore(envelope: ByteArray, password: CharArray): Int {
        val bytes = BackupCipher.decrypt(envelope, password)
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("format") == 1) { "Unsupported backup version." }
            val sources = root.getJSONArray("sources")
            val sourceRows = (0 until sources.length()).map { i ->
                val json = sources.getJSONObject(i)
                val id = json.getString("id")
                require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid source identifier." }
                val parts = json.getJSONArray("parts")
                for (j in 0 until parts.length()) parts.getJSONObject(j).put("file", File(app.filesDir, "restored-sources/$id/$j.apk").absolutePath)
                val parsed = JsonCodec.source(json.toString())
                require(parsed.parts.count { it.splitName == null } == 1)
                SourceRow(id, parsed.label, parsed.packageName, JsonCodec.source(parsed))
            }
            val array = root.getJSONArray("clones")
            val records = (0 until array.length()).map { index ->
                val record = array.getJSONObject(index)
                val plan = JsonCodec.plan(record.getJSONObject("plan").toString())
                require(plan.id.matches(Regex("[a-f0-9]{32}")) && plan.packageName == "dev.multiclone.generated.c${plan.id}" && plan.engineVersion == 1)
                require(plan.label.isNotBlank() && plan.label.length <= 100 && plan.icon == null)
                require(plan.identityMap.size <= 10_000 && plan.identityMap.all { it.key.length <= 1024 && it.value.startsWith(plan.packageName) })
                val sourceId = record.getString("sourceId")
                require(sourceRows.any { it.id == sourceId }) { "Backup is missing source metadata." }
                Triple(plan, sourceId, record)
            }
            require(records.map { it.first.id }.distinct().size == records.size) { "Duplicate backup clone records." }
            // Verify every key before modifying database records. Existing keys cannot be overwritten.
            for ((plan, _, record) in records) {
                val raw = Base64.decode(record.getString("key"), Base64.NO_WRAP)
                try { app.vault.importRaw(plan.id, raw) } finally { raw.fill(0) }
            }
            for (row in sourceRows) if (app.dao.source(row.id) == null) app.dao.saveSource(row)
            for ((plan, sourceId, record) in records) if (app.dao.clone(plan.id) == null) {
                app.dao.saveClone(CloneRow(plan.id, sourceId, plan.label, plan.packageName, JsonCodec.plan(plan), versionCode = record.getLong("version"),
                    state = "RECOVERED", message = "Signing key restored. Import the source APK and use Update to rebuild; app data is managed by Android."))
            }
            return records.size
        } finally { bytes.fill(0) }
    }
}
