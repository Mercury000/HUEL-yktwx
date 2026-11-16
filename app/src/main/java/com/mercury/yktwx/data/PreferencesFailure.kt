package com.mercury.yktwx.data

import android.system.ErrnoException
import android.system.OsConstants
import androidx.datastore.core.CorruptionException
import java.io.IOException

internal enum class PreferencesFailureKind { Format, Record, Policy, Migration, Storage, Internal }

/** Only controlled descriptions reach the UI; parser errors can contain the entire stored JSON. */
internal data class PreferencesFailure(val kind: PreferencesFailureKind, val detail: String) {
    companion object {
        fun from(error: Exception): PreferencesFailure {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
            causes.filterIsInstance<PreferencesDataException>().firstOrNull()?.let { return it.failure }
            val errno = causes.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            val storageDetail = when (errno) {
                OsConstants.ENOSPC -> "本机存储空间不足，无法读写设置文件"
                OsConstants.EACCES, OsConstants.EPERM -> "无法访问本机设置文件，文件访问被拒绝"
                OsConstants.EROFS -> "本机设置所在存储为只读，无法完成保存或迁移"
                OsConstants.EIO -> "本机存储读写异常，无法读取设置文件"
                else -> null
            }
            if (storageDetail != null) return PreferencesFailure(PreferencesFailureKind.Storage, storageDetail)
            if (error is CorruptionException) return PreferencesFailure(PreferencesFailureKind.Format,
                "本机设置文件格式损坏，无法解析 app-preferences.json")
            val ioError = causes.filterIsInstance<IOException>().firstOrNull()
            if (ioError != null) return PreferencesFailure(PreferencesFailureKind.Storage,
                "本机设置文件读写失败（${ioError.javaClass.simpleName}）")
            return PreferencesFailure(PreferencesFailureKind.Internal,
                "本机设置处理发生异常（${error.javaClass.simpleName}）")
        }
    }
}

internal class PreferencesDataException(val failure: PreferencesFailure, cause: Throwable? = null) :
    IllegalArgumentException(failure.detail, cause)

internal fun requirePreferences(condition: Boolean, kind: PreferencesFailureKind, detail: String) {
    if (!condition) throw PreferencesDataException(PreferencesFailure(kind, detail))
}

internal fun policyChannelLabel(key: String): String = when (key.substringAfterLast(':')) {
    "stable" -> "正式版"
    "beta" -> "测试版"
    else -> "本地"
}
