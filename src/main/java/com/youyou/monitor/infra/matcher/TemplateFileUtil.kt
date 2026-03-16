package youyou.monitor.screen.infra.matcher

import java.io.File

object TemplateFileUtil {
    val REMOTE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")
    val LOCAL_IMAGE_EXTENSIONS = REMOTE_IMAGE_EXTENSIONS + setOf("tmp_png", "tmp_jpg")

    fun isRemoteImageName(name: String): Boolean {
        val idx = name.lastIndexOf('.')
        if (idx <= 0) return false
        val ext = name.substring(idx + 1).lowercase()
        return REMOTE_IMAGE_EXTENSIONS.contains(ext)
    }

    fun isLocalImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return LOCAL_IMAGE_EXTENSIONS.contains(ext)
    }

    fun normalizeLocalToRemote(localName: String): String {
        val tmpIndex = localName.lastIndexOf(".tmp_")
        return if (tmpIndex != -1) {
            localName.substring(0, tmpIndex) + "." + localName.substring(tmpIndex + 5)
        } else {
            localName
        }
    }

    fun remoteToLocalName(remoteName: String, preferExternal: Boolean): String {
        if (!preferExternal) return remoteName
        val idx = remoteName.lastIndexOf('.')
        if (idx <= 0) return remoteName
        val base = remoteName.substring(0, idx)
        val ext = remoteName.substring(idx + 1)
        return "$base.tmp_$ext"
    }
}
