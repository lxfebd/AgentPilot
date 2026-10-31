package com.agentpilot.app.feature.auth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the session store helpers; no Robolectric needed. */
class AuthSessionStoreTest {

    @Test
    fun legacyKeysCoverEveryStoredSessionField() {
        assertEquals(
            listOf(
                "server_url",
                "access_token",
                "token_type",
                "user_id",
                "role",
                "refresh_token",
                "refresh_expires_at",
            ),
            AuthSessionStore.LEGACY_KEYS,
        )
    }

    @Test
    fun migrationCopiesEveryLegacyValueAndDeletesPlaintext() {
        val legacy = FakeSharedPreferences(
            mapOf(
                "server_url" to "https://pilot.example.com",
                "access_token" to "access-1",
                "token_type" to "bearer",
                "user_id" to "user-1",
                "role" to "user",
                "refresh_token" to "refresh-1",
                "refresh_expires_at" to "2026-11-30T00:00:00Z",
            ),
        )
        val encrypted = FakeSharedPreferences()
        var deleted = false

        val completed = migrateLegacySessionValues(legacy, encrypted) { deleted = true }

        assertTrue(completed)
        assertTrue(deleted)
        for (key in AuthSessionStore.LEGACY_KEYS) {
            assertEquals(legacy.getString(key, null), encrypted.getString(key, null))
        }
        assertEquals("refresh-1", encrypted.getString("refresh_token", null))
    }

    @Test
    fun migrationKeepsPlaintextWhenTheEncryptedCommitFails() {
        val legacy = FakeSharedPreferences(mapOf("access_token" to "access-1"))
        val encrypted = FakeSharedPreferences().apply { commitResult = false }
        var deleted = false

        val completed = migrateLegacySessionValues(legacy, encrypted) { deleted = true }

        assertFalse(completed)
        // The plaintext copy must survive so a later launch can retry.
        assertFalse(deleted)
        assertNull(encrypted.getString("access_token", null))
        assertEquals("access-1", legacy.getString("access_token", null))
    }

    @Test
    fun emptyLegacyStoreIsDeletedWithoutWritingAnything() {
        val legacy = FakeSharedPreferences()
        val encrypted = FakeSharedPreferences()
        var deleted = false

        val completed = migrateLegacySessionValues(legacy, encrypted) { deleted = true }

        assertTrue(completed)
        assertTrue(deleted)
        assertTrue(encrypted.values.isEmpty())
    }

    @Test
    fun unauthorizedTokenOnlyClearsWhenItMatchesTheStoredAccessToken() {
        assertTrue(shouldClearAuthSession("access-1", "access-1"))
        assertFalse(shouldClearAuthSession("access-1", "access-2"))
        // A 401 without a bearer token (anonymous request) must not sign the user out.
        assertFalse(shouldClearAuthSession("access-1", ""))
        assertFalse(shouldClearAuthSession("", ""))
    }
}

/** In-memory [SharedPreferences] so the migration logic can be exercised off-device. */
private class FakeSharedPreferences(
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {
    val values: MutableMap<String, Any?> = LinkedHashMap(initial)
    var commitResult: Boolean = true

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? {
        return values[key] as? String ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return (values[key] as? Number)?.toInt() ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return (values[key] as? Number)?.toLong() ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        return (values[key] as? Number)?.toFloat() ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return values[key] as? Boolean ?: defValue
    }

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakeEditor(private val preferences: FakeSharedPreferences) : SharedPreferences.Editor {
    private val puts = LinkedHashMap<String, Any?>()
    private val removals = LinkedHashSet<String>()
    private var clearAll = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
        put(key, values)

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

    override fun remove(key: String?): SharedPreferences.Editor = apply {
        if (key != null) removals.add(key)
    }

    override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

    override fun commit(): Boolean {
        if (!preferences.commitResult) return false
        applyChanges()
        return true
    }

    override fun apply() {
        applyChanges()
    }

    private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
        if (key != null) puts[key] = value
    }

    private fun applyChanges() {
        if (clearAll) preferences.values.clear()
        for (key in removals) preferences.values.remove(key)
        preferences.values.putAll(puts)
    }
}
