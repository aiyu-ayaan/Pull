package com.aiyu.pull

import com.aiyu.pull.update.Release
import com.aiyu.pull.update.ReleaseAsset
import com.aiyu.pull.update.Releases
import com.aiyu.pull.update.UpdateChannel
import com.aiyu.pull.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {
    private fun release(v: String, vararg abis: String) = Release(
        Version.parse(v)!!, v, "", "", abis.map { ReleaseAsset("Pull-$v-$it.apk", "u/$it", 1) },
    )

    @Test fun picksNewestOnChannel() {
        val all = listOf(release("0.1.1-alpha.1", "x86"), release("0.1.1-alpha.2", "x86"), release("0.1.0", "x86"))
        assertEquals("0.1.1-alpha.2", Releases.pick(all, Version.parse("0.1.1-alpha.1"), UpdateChannel.ALPHA)?.name)
        assertNull(Releases.pick(all, Version.parse("0.1.0"), UpdateChannel.STABLE))
    }

    @Test fun apkMatchesDeviceAbiInPreferenceOrder() {
        val r = release("1.0.0", "x86_64", "x86", "armeabi-v7a")
        assertEquals("Pull-1.0.0-x86_64.apk", Releases.apkFor(r, listOf("x86_64", "x86"))?.name)
        assertEquals("Pull-1.0.0-x86.apk", Releases.apkFor(r, listOf("x86"))?.name) // not x86_64
        assertEquals("Pull-1.0.0-armeabi-v7a.apk", Releases.apkFor(r, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertNull(Releases.apkFor(release("1.0.0", "x86"), listOf("arm64-v8a")))
    }
}
