package io.clroot.build

/**
 * Matches the tasks that publish a *release* to Maven Central, across both the `nmcp` and
 * `maven-publish` naming variants (`publishAggregationToCentralPortal`,
 * `nmcpPublishAllPublicationsToCentralPortal`, `publishMavenPublicationToNmcpRepository`, ...).
 *
 * The trailing anchor deliberately excludes the `...Snapshots` variants: snapshot publication must
 * stay usable without a release version or PGP credentials, which the release gates would reject.
 */
val CENTRAL_RELEASE_TASK_PATTERN = Regex("^(nmcp)?[Pp]ublish.*To(CentralPortal|NmcpRepository)$")

/** Substrings that prove a test-fixture variant or dependency leaked into published metadata. */
val TEST_FIXTURE_LEAK_MARKERS = setOf(
    "testFixturesApiElements",
    "testFixturesRuntimeElements",
    "-test-fixtures.jar",
    "spring-boot-starter-test",
    "io.kotest",
    "org.testcontainers",
)
