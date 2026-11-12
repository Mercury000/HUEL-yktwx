package com.mercury.yktwx.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class AvatarDecoder @Inject constructor(@ApplicationContext private val context: Context) {
    fun decode(uri: Uri): Bitmap = AvatarImage.decode(context.contentResolver, uri)
}
