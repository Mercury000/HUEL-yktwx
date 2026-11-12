package com.mercury.yktwx.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Transfer/installation fixtures bypass probing; AppUpdateSourceSelectionTest exercises the real selector.
internal fun appUpdateInstallerFixture(context: Context, http: UpdateHttp = UpdateHttp(),
    contentUri: (File) -> Uri = {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
    }): AppUpdateInstaller = AppUpdateInstaller(context = context, http = http, sourceSelectionMillis = 0, contentUri = contentUri)
