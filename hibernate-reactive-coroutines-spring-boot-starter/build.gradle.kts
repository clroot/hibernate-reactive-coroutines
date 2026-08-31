plugins {
    id("hrc.boot-starter")
}

// Everything else lives in the `hrc.boot-starter` convention; this module only pins its platform.
// `endorseStrictVersions` is explicit because catalog-provider platforms, unlike string notation,
// do not set it, and dropping it would weaken version enforcement for published consumers.
dependencies {
    api(platform(libs.spring.boot3.bom)) {
        endorseStrictVersions()
    }
    testFixturesImplementation(platform(libs.spring.boot3.bom)) {
        endorseStrictVersions()
    }
}
