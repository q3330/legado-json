package io.legado.app.help.storage

import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openOutputStream
import splitties.init.appCtx
import java.io.File

/**
 * 统一解析视频与漫画缓存的备份根目录：
 * - 优先直接写入备份目录对应的本地文件系统路径（例如 `<备份目录>/视频`、`<备份目录>/漫画/<书名>/<章节名>`）；
 * - 若备份目录为 SAF `content://` 且当前无直接文件系统写权限，则先落盘到应用外部存储目录以保障本地播放/Glide读取，
 *   并在文件写入完成后自动镜像同步到 SAF 备份目录中。
 */
object BackupMediaStorage {

    const val VIDEO_FOLDER = "视频"
    const val MANGA_FOLDER = "漫画"

    private val invalidNameChars = Regex("""[\\/:*?"<>|\r\n\t]+""")

    fun sanitizeName(raw: String?, fallback: String = "未命名"): String {
        val cleaned = raw
            ?.replace(invalidNameChars, "_")
            ?.trim()
            ?.trim('.')
            .orEmpty()
            .take(80)
            .trim()
        return cleaned.ifBlank { fallback }
    }

    /**
     * 解析可直接用 [File] 读写的备份根目录。
     * 若 `AppConfig.backupPath` 未设置或为不可直接写文件的 SAF URI，返回 null。
     */
    fun resolveDirectBackupDir(): File? {
        val backupPath = AppConfig.backupPath?.trim().orEmpty()
        if (backupPath.isEmpty()) return null
        val candidate = if (backupPath.isContentScheme()) {
            tryResolveSafTreeToFile(backupPath.toUri())
        } else {
            File(backupPath)
        } ?: return null
        return candidate.takeIf { ensureWritableDir(it) }
    }

    /**
     * 获取本地 [File] 形式的视频缓存根目录（优先备份目录/视频，回退到 externalFiles/视频）。
     */
    fun videoRootDir(): File {
        val base = resolveDirectBackupDir() ?: appCtx.externalFiles
        return File(base, VIDEO_FOLDER).apply { mkdirs() }
    }

    /**
     * 获取本地 [File] 形式的漫画缓存根目录（优先备份目录/漫画，回退到 externalFiles/漫画）。
     */
    fun mangaRootDir(): File {
        val base = resolveDirectBackupDir() ?: appCtx.externalFiles
        return File(base, MANGA_FOLDER).apply { mkdirs() }
    }

    fun mangaBookDir(bookTitle: String): File {
        return File(mangaRootDir(), sanitizeName(bookTitle, "未命名漫画"))
    }

    fun mangaChapterDir(bookTitle: String, chapterTitle: String): File {
        return File(mangaBookDir(bookTitle), sanitizeName(chapterTitle, "未命名章节"))
    }

    /**
     * 当 `AppConfig.backupPath` 是 SAF `content://` 且无法直接 File 写入时，
     * 将已写好的本地文件镜像复制到 SAF 备份目录对应子路径下。
     */
    fun mirrorToSafBackupIfNeeded(sourceFile: File, subDirs: List<String>, fileName: String) {
        if (!sourceFile.isFile) return
        val backupPath = AppConfig.backupPath?.trim().orEmpty()
        if (!backupPath.isContentScheme()) return
        if (resolveDirectBackupDir() != null) return
        runCatching {
            val rootDoc = FileDoc.fromUri(backupPath.toUri(), true)
            val targetDir = subDirs.fold(rootDoc) { acc, dirName ->
                acc.createFolderIfNotExist(dirName)
            }
            val targetDoc = targetDir.createFileIfNotExist(fileName)
            targetDoc.openOutputStream().getOrThrow().use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
        }
    }

    private fun tryResolveSafTreeToFile(uri: Uri): File? {
        val raw = Uri.decode(uri.toString())
        val treePart = raw.substringAfter("/tree/", "")
        if (treePart.isEmpty()) return null
        val docPart = treePart.substringBefore("/document/")
        if (docPart.startsWith("primary:", ignoreCase = true)) {
            val relPath = docPart.substringAfter("primary:", "").trimStart('/')
            val storageRoot = Environment.getExternalStorageDirectory()
            return if (relPath.isEmpty()) storageRoot else File(storageRoot, relPath)
        }
        return null
    }

    private fun ensureWritableDir(dir: File): Boolean {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            if (!dir.isDirectory) return@runCatching false
            val probe = File(dir, ".legado_write_probe")
            if (probe.exists() || probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
