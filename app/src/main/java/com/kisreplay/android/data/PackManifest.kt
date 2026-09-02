package com.kisreplay.android.data

import org.json.JSONObject

data class PackFile(
    val role: String,
    val path: String,
    val sha256: String,
    val size: Long,
)

data class PackManifest(
    val formatVersion: Int,
    val packId: String,
    val createdAt: String,
    val sourceVersion: String,
    val files: List<PackFile>,
) {
    companion object {
        fun fromJson(text: String): PackManifest {
            val root = JSONObject(text)
            val arr = root.getJSONArray("files")
            val files = buildList {
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    add(
                        PackFile(
                            role = x.getString("role"),
                            path = x.getString("path"),
                            sha256 = x.getString("sha256"),
                            size = x.getLong("size"),
                        )
                    )
                }
            }
            return PackManifest(
                formatVersion = root.getInt("format_version"),
                packId = root.getString("pack_id"),
                createdAt = root.optString("created_at"),
                sourceVersion = root.optString("source_version"),
                files = files,
            )
        }
    }
}

data class ImportedPack(
    val dir: java.io.File,
    val manifest: PackManifest,
) {
    fun file(role: String): java.io.File? {
        val item = manifest.files.firstOrNull { it.role == role } ?: return null
        return java.io.File(dir, item.path)
    }
}
