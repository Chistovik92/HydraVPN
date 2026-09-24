package ru.gidravpn.hydra.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingProfilesCodecTest {
    @Test fun roundTripKeepsTypes() {
        val p = RoutingProfilesRepository.Profile(
            name = "Дом",
            routing = mapOf("dns_provider" to "CUSTOM", "geo_countries" to "by,ru"),
            split = mapOf("split_mode" to "EXCLUDE", "split_packages" to "[\"com.bank\"]"),
        )
        val back = RoutingProfilesRepository.decode(RoutingProfilesRepository.encode(listOf(p)))
        assertEquals(listOf(p), back)
    }

    @Test fun garbageDecodesToEmpty() =
        assertEquals(emptyList<RoutingProfilesRepository.Profile>(), RoutingProfilesRepository.decode("{oops"))
}
