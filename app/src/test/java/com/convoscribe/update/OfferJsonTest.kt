package com.convoscribe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [OfferJson]: how [PrefsUpdateStore] keeps the found offer (issue #41). */
class OfferJsonTest {

    private val apk = ReleaseAsset("convoscribe-0.7.0.apk", "https://x/convoscribe-0.7.0.apk", 52_000_000L)
    private val sha = ReleaseAsset("convoscribe-0.7.0.apk.sha256", "https://x/convoscribe-0.7.0.apk.sha256", 64L)

    private fun offer(sha256: ReleaseAsset?) = UpdateOffer(
        version = SemVer.parse("0.7.0-rc.1")!!,
        release = Release(
            tag = "v0.7.0-rc.1",
            name = "ConvoScribe 0.7.0 RC 1",
            notes = "Notes with \"quotes\" and\nlines",
            htmlUrl = "https://github.com/edkief/convoscribe/releases/tag/v0.7.0-rc.1",
            prerelease = true,
            draft = false,
            assets = listOfNotNull(apk, sha256)
        ),
        apk = apk,
        sha256 = sha256
    )

    @Test
    fun roundTrip_keepsTheOffer() {
        assertEquals(offer(sha), OfferJson.decode(OfferJson.encode(offer(sha))))
        assertEquals(offer(null), OfferJson.decode(OfferJson.encode(offer(null))))
    }

    @Test
    fun unreadableValue_isNull() {
        assertNull(OfferJson.decode("not json"))
        assertNull(OfferJson.decode("{}"))
        assertNull(OfferJson.decode(OfferJson.encode(offer(sha)).replace("0.7.0-rc.1\"", "seven\"")))
    }
}
