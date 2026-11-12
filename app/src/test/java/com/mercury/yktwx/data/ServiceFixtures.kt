package com.mercury.yktwx.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

internal open class HttpFixture(url: URL) : HttpsURLConnection(url) {
    open fun statusCode(): Int = 200
    open fun contentLength(): Long = -1
    open fun bodyStream(): InputStream = error("No fixture body")
    open fun close() {}
    override fun getResponseCode() = statusCode()
    override fun getContentLengthLong() = contentLength()
    override fun getInputStream() = bodyStream()
    override fun disconnect() = close()
    override fun connect() {}
    override fun usingProxy() = false
    override fun getCipherSuite() = "fixture"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
internal fun fixtureUpdateHttp(metadataTimeoutMillis: Long = UpdateHttp.DEFAULT_METADATA_TIMEOUT_MILLIS,
    open: (URL) -> HttpFixture): UpdateHttp = UpdateHttp(metadataTimeoutMillis, open)

// Transfer/installation fixtures bypass probing; AppUpdateSourceSelectionTest exercises the real selector.
internal fun appUpdateInstallerFixture(context: Context, http: UpdateHttp = UpdateHttp(),
    contentUri: (File) -> Uri = {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
    }): AppUpdateInstaller = AppUpdateInstaller(context = context, http = http, sourceSelectionMillis = 0, contentUri = contentUri)
