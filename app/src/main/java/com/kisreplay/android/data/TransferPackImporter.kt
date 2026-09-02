package com.kisreplay.android.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class TransferPackImporter(private val context: Context) {
    private val prefs = context.getSharedPreferences("kis_pack", Context.MODE_PRIVATE)

    fun import(uri: Uri): ImportedPack {
        val tmpRoot = File(context.cacheDir, "pack_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "전송팩 파일을 열 수 없습니다" }
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = safeTarget(tmpRoot, entry.name)
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { dst -> zip.copyTo(dst) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val manifestFile = File(tmpRoot, "manifest.json")
            require(manifestFile.isFile) { "manifest.json이 없는 전송팩입니다" }
            val manifest = PackManifest.fromJson(manifestFile.readText(Charsets.UTF_8))
            require(manifest.formatVersion in 1..2) { "지원하지 않는 전송팩 버전: ${manifest.formatVersion}" }

            for (item in manifest.files) {
                val f = safeTarget(tmpRoot, item.path)
                require(f.isFile) { "전송팩 파일 누락: ${item.path}" }
                require(f.length() == item.size) { "파일 크기 불일치: ${item.path}" }
                require(sha256(f).equals(item.sha256, ignoreCase = true)) { "무결성 검사 실패: ${item.path}" }
            }

            val packsRoot = File(context.filesDir, "packs").apply { mkdirs() }
            val finalDir = File(packsRoot, manifest.packId)
            if (finalDir.exists()) finalDir.deleteRecursively()
            require(tmpRoot.renameTo(finalDir)) { "전송팩을 내부 저장소로 이동하지 못했습니다" }
            prefs.edit().putString("active_pack_id", manifest.packId).apply()
            return ImportedPack(finalDir, manifest)
        } catch (e: Exception) {
            tmpRoot.deleteRecursively()
            throw e
        }
    }

    fun loadActive(): ImportedPack? {
        val id = prefs.getString("active_pack_id", null) ?: return null
        val dir = File(File(context.filesDir, "packs"), id)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        return runCatching { ImportedPack(dir, PackManifest.fromJson(manifestFile.readText(Charsets.UTF_8))) }.getOrNull()
    }

    private fun safeTarget(root: File, relative: String): File {
        val target = File(root, relative)
        val rootPath = root.canonicalPath + File.separator
        val targetPath = target.canonicalPath
        require(targetPath.startsWith(rootPath) || targetPath == root.canonicalPath) { "잘못된 ZIP 경로" }
        return target
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
