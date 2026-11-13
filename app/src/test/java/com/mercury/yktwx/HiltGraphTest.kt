package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [35])
class HiltGraphTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @javax.inject.Inject internal lateinit var repository: AccountRepository
    @javax.inject.Inject internal lateinit var authority: SessionAuthority
    @javax.inject.Inject internal lateinit var preferences: AppPreferencesRepository
    @javax.inject.Inject internal lateinit var analytics: AppAnalytics
    @javax.inject.Inject internal lateinit var http: AppHttp
    @javax.inject.Inject internal lateinit var hellobike: HellobikeService
    @javax.inject.Inject internal lateinit var hellobikeStore: HellobikeStore
    @javax.inject.Inject internal lateinit var hellobikeLocation: HellobikeLocationService
    @javax.inject.Inject @com.mercury.yktwx.di.ServiceClient internal lateinit var serviceClient: okhttp3.Call.Factory
    @javax.inject.Inject @com.mercury.yktwx.di.PublicClient internal lateinit var publicClient: okhttp3.Call.Factory
    @javax.inject.Inject @com.mercury.yktwx.di.UpdateClient internal lateinit var updateClient: okhttp3.Call.Factory

    @Test fun activityAndViewModelsShareTheGraphAndTheOriginalActivityOwner() {
        hilt.inject()
        runBlocking { preferences.awaitReady() }
        assertSame(repository, authority)
        assertSame(http.service, serviceClient)
        assertSame(http.publicRead, publicClient)
        assertSame(http.update, updateClient)
        assertNotSame(http.service.connectionPool, http.publicRead.connectionPool)
        assertSame(http.publicRead.connectionPool, http.update.connectionPool)
        assertSame(http.service.dispatcher, http.update.dispatcher)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(preferences, activity.preferences)
                assertSame(preferences, activity.settings.repository)
                assertSame(analytics, activity.analytics)
                val provider = ViewModelProvider(activity)
                fun activityModel(name: String): Any? {
                    val field = MainActivity::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
                    return (field.get(activity) as Lazy<*>).value
                }
                val payment = provider[PaymentViewModel::class.java]
                assertSame(activityModel("viewModel"), payment)
                assertSame(activityModel("billViewModel"), provider[BillViewModel::class.java])
                val repositoryField = PaymentViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }
                assertSame(repository, repositoryField.get(payment))
                assertNotSame(repository.overviewReads, repository.cardReads)
                val bike = provider["hellobike-false", HellobikeViewModel::class.java]
                assertSame(bike, ViewModelProvider(activity)["hellobike-false", HellobikeViewModel::class.java])
                fun dependency(name: String) = HellobikeViewModel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(bike)
                assertSame(hellobike, dependency("api"))
                assertSame(hellobikeStore, dependency("store"))
                assertSame(hellobikeLocation, dependency("location"))
                assertSame(serviceClient, HellobikeApi::class.java.getDeclaredField("client")
                    .apply { isAccessible = true }.get(hellobike))
                assertTrue(bike.state.value.restoring) // Construction alone must not read account storage.
            }
        }
    }
}
