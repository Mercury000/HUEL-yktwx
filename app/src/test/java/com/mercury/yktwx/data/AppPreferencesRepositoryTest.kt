package com.mercury.yktwx.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesRepositoryTest {
    @Test fun persistedWriteWinsOverDelayedOlderObserverEmission() = runTest {
        val emissions = Channel<AppPreferences>(Channel.UNLIMITED)
        var persisted = AppPreferences(legacyMigrated = true)
        val store = object : DataStore<AppPreferences> {
            override val data = emissions.receiveAsFlow()
            override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences): AppPreferences {
                persisted = transform(persisted)
                return persisted
            }
        }
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        emissions.send(persisted); runCurrent()
        val first = repository.update { it.copy(updateChannel = "beta") }
        val second = repository.update { it.copy(ui = it.ui.copy(preventScreenshots = false)) }
        emissions.send(first); runCurrent()
        assertEquals(second, repository.snapshot())
        assertEquals(second, (repository.composeState.value as PreferencesLoadState.Ready).preferences)
    }

    @Test fun loadingAndReadFailureNeverExposeAnEmptyPolicyAndCanRetry() = runTest {
        var fail = true
        val good = AppPreferences(legacyMigrated = true, updateChannel = "beta")
        val store = object : DataStore<AppPreferences> {
            override val data = flow { if (fail) throw java.io.IOException("read failed") else emit(good); awaitCancellation() }
            override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences) = transform(good)
        }
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        assertEquals(PreferencesLoadState.Loading, repository.state.value)
        runCurrent()
        assertEquals(PreferencesLoadState.Failed, repository.state.value)
        assertNull(repository.readyOrNull())
        fail = false; repository.retry(); runCurrent()
        assertEquals(good, repository.awaitReady())
    }

    @Test fun serializerRoundTripsAndCorruptionIsNeverDefaultReset() = runTest {
        val fixture = SignedUpdateFixture()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        val data = AppPreferences(legacyMigrated = true, writeRevision = 4, updateChannel = "beta",
            roomSelections = mapOf("owner.selection" to setOf("target")), ui = UiPreferences(preventScreenshots = false),
            policies = mapOf("${fixture.request.packageName}:stable" to AcceptedUpdatePolicy(release.policyRevision,
                digest, 9, release.versionCode, release.signedManifest)))
        val output = ByteArrayOutputStream()
        AppPreferencesSerializer.writeTo(data, output)
        val decoded = AppPreferencesSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(data, decoded)
        val restored = appPreferencesRepositoryFixture(MemoryPreferences(decoded), backgroundScope, fixture.verifier::manifest)
        runCurrent()
        assertEquals(data, restored.awaitReady())
        for (body in listOf("invalid", "[]", "{}", "{} trailing", "{\"schemaVersion\":2}", "{\"writeRevision\":-1}")) {
            assertThrows(androidx.datastore.core.CorruptionException::class.java) { runBlocking {
                AppPreferencesSerializer.readFrom(ByteArrayInputStream(body.toByteArray()))
            } }
        }
    }

    @Test fun concurrentPolicyAcceptanceIsAtomicAndRejectsRollbackAndDigestConflicts() = runTest {
        val fixture = SignedUpdateFixture()
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val updates = UpdateCheckStore(repository, fixture.request.packageName, 9,
            verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
        fun policy(revision: Long, version: Int, forced: Int) = fixture.verifier.manifest(
            fixture.sign(fixture.json(version, revision).withField("maxForcedVersionCode", forced)), "stable")
        val high = policy(3, 12, 9)
        val low = policy(2, 11, 8)
        coroutineScope {
            listOf(high, low).map { (release, digest) -> async { runCatching { updates.acceptPolicy("stable", release, digest) } } }.awaitAll()
        }
        val accepted = repository.snapshot().policies.values.single()
        assertEquals(3L, accepted.revision)
        assertEquals(high.second, accepted.digest)
        assertEquals(9, updates.forcedBoundary())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", low.first, low.second) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", high.first, "b".repeat(64)) } }
        val reset = policy(4, 13, 0)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", reset.first, reset.second) } }
        assertEquals(accepted, repository.snapshot().policies.values.single())
    }

    @Test fun tamperedPersistedManifestBlocksReadinessWithoutClearingTheLedger() = runTest {
        val fixture = SignedUpdateFixture()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        val policy = AcceptedUpdatePolicy(release.policyRevision, digest, 9, release.versionCode, release.signedManifest.replace("\"signature\":\"", "\"signature\":\"A"))
        val data = AppPreferences(legacyMigrated = true, policies = mapOf("${fixture.request.packageName}:stable" to policy))
        val store = MemoryPreferences(data)
        val repository = appPreferencesRepositoryFixture(store, backgroundScope, fixture.verifier::manifest)
        runCurrent()
        assertEquals(PreferencesLoadState.Failed, repository.state.value)
        assertEquals(data, store.values.value)
    }
}
