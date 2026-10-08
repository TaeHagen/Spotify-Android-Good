package com.taehagen.spotifygood.engine

import com.taehagen.spotifygood.auth.AccountOwnerFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AccountGuardTest {
    /** What the device keeps of an account, removed by the wipe like AppGraph's account data steps. */
    private class DeviceData {
        val downloads = mutableSetOf("spotify:track:a1", "spotify:track:a2")
        val responseCache = mutableMapOf("home" to "A's home", "library" to "A's library")
        val recentSearches = mutableListOf("A's search")
        var wipes = 0

        suspend fun wipe() {
            runWipeSteps(
                listOf(
                    WipeStep("downloads") { downloads.clear() },
                    WipeStep("response cache") { responseCache.clear() },
                    WipeStep("database") { recentSearches.clear() },
                ),
            )
            wipes++
        }

        val empty get() = downloads.isEmpty() && responseCache.isEmpty() && recentSearches.isEmpty()
    }

    private fun ownerFile(): AccountOwnerFile {
        val dir = Files.createTempDirectory("owner").toFile().apply { deleteOnExit() }
        return AccountOwnerFile(File(dir, "account_owner"))
    }

    private fun guard(owner: AccountOwnerFile, data: DeviceData) =
        AccountGuard(owner::read, owner::write) { data.wipe() }

    @Test
    fun anotherAccountAfterRejectedCredentialsStartsClean() = runTest {
        // A logged in once; Spotify then rejected A's credentials. Only credentials.bin went:
        // the owner file still names A.
        val owner = ownerFile().apply { write("alice") }
        val data = DeviceData()
        // B approves the device code with B's own account.
        assertTrue(guard(owner, data).adopt("bob"))
        assertTrue("A's downloads, cache and searches are gone", data.empty)
        assertEquals("bob", owner.read())
        // B's own data from now on stays with B.
        data.recentSearches += "B's search"
        assertFalse(guard(owner, data).adopt("bob"))
        assertEquals(listOf("B's search"), data.recentSearches)
    }

    @Test
    fun aReloginOfTheSameAccountKeepsEverything() = runTest {
        val owner = ownerFile().apply { write("alice") }
        val data = DeviceData()
        assertFalse(guard(owner, data).adopt("alice"))
        assertFalse("usernames don't differ by case", guard(owner, data).adopt("Alice"))
        assertEquals(0, data.wipes)
        assertEquals(2, data.downloads.size)
        assertEquals(2, data.responseCache.size)
        assertEquals(1, data.recentSearches.size)
    }

    @Test
    fun aFailedWipeFailsTheLoginAndTheNextOneWipesAgain() = runTest {
        val owner = ownerFile().apply { write("alice") }
        val data = DeviceData()
        var failNext = true
        val guard = AccountGuard(owner::read, owner::write) {
            if (failNext) {
                failNext = false
                data.downloads.clear() // partly done
                throw IOException("database busy")
            }
            data.wipe()
        }
        try {
            guard.adopt("bob")
            fail("the login must fail")
        } catch (e: IOException) {
            // expected
        }
        assertEquals("still A's: the next login wipes again", "alice", owner.read())
        assertTrue(guard.adopt("bob"))
        assertTrue(data.empty)
        assertEquals("bob", owner.read())
    }

    @Test
    fun nobodyOwnsAFreshDevice() = runTest {
        val owner = ownerFile()
        val data = DeviceData()
        assertFalse(guard(owner, data).adopt("bob"))
        assertEquals(0, data.wipes)
        assertEquals("bob", owner.read())

        // Without an owner a failed write doesn't fail the login (no other account's data).
        val failures = mutableListOf<Throwable>()
        val readOnly = AccountGuard({ null }, { throw IOException("disk full") }, { failures += it }) { data.wipe() }
        assertFalse(readOnly.adopt("bob"))
        assertEquals(1, failures.size)
        // A blank username is never another account.
        assertFalse(guard(ownerFile().apply { write("alice") }, data).adopt("  "))
        assertEquals(0, data.wipes)
    }

    @Test
    fun theOwnerFile() {
        val owner = ownerFile()
        assertNull(owner.read())
        owner.write("  alice \n")
        assertEquals("alice", owner.read())
        owner.write(" ")
        assertEquals("a blank name changes nothing", "alice", owner.read())
        owner.delete()
        assertNull(owner.read())
        owner.delete() // already gone
    }

    @Test
    fun everyWipeStepRunsAndTheFirstFailureIsRethrown() = runTest {
        val ran = mutableListOf<String>()
        val failed = mutableListOf<String>()
        try {
            runWipeSteps(
                listOf(
                    WipeStep("a") { ran += "a"; throw IOException("first") },
                    WipeStep("b") { ran += "b"; throw IllegalStateException("second") },
                    WipeStep("c") { ran += "c" },
                ),
            ) { step, _ -> failed += step.name }
            fail("must rethrow")
        } catch (e: IOException) {
            assertEquals("first", e.message)
        }
        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(listOf("a", "b"), failed)
    }

    @Test
    fun anotherAccount() {
        assertFalse(isAnotherAccount(null, "bob"))
        assertFalse(isAnotherAccount("bob", "BOB "))
        assertTrue(isAnotherAccount("alice", "bob"))
    }
}
