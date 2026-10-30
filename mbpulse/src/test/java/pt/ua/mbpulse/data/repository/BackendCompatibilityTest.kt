package pt.ua.mbpulse.data.repository

import pt.ua.mbpulse.data.repository.BackendCompatibility.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendCompatibilityTest {

    private fun check(api: Int, since: Int, missing: List<String> = emptyList(), client: Int = 3) =
        BackendCompatibility.check(BackendVersion(api, since, missing), client)

    @Test fun sameVersion() = assertEquals(Result.Compatible, check(api = 3, since = 3))

    @Test fun newerBackendStillServingThisApp() = assertEquals(Result.Compatible, check(api = 5, since = 2))

    @Test fun backendOlderThanApp() = assertEquals(Result.BackendTooOld(2), check(api = 2, since = 2))

    @Test fun backendBeforeVersioning() = assertEquals(Result.BackendTooOld(0), check(api = 0, since = 0))

    @Test fun breakingBackendChange() = assertEquals(Result.AppTooOld(4), check(api = 4, since = 4))

    @Test fun missingFilesWin() =
        assertEquals(Result.Incomplete(listOf("retention")), check(api = 3, since = 3, missing = listOf("retention")))

    @Test fun clientDefaultIsCurrentApi() =
        assertEquals(Result.Compatible,
            BackendCompatibility.check(BackendVersion(BackendCompatibility.CLIENT_API, BackendCompatibility.CLIENT_API)))
}
