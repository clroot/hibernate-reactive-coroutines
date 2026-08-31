import io.clroot.build.TEST_FIXTURE_LEAK_MARKERS
import io.clroot.build.VerifyPublicationMetadata
import io.clroot.build.lib
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata

/**
 * Spring Boot starter baseline.
 *
 * Every starter cross-compiles the same shared source tree against a different Spring Boot platform,
 * so this plugin owns everything except the platform itself: applying module declares only its BOM.
 */

plugins {
    id("hrc.published")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.jetbrains.kotlin.plugin.jpa")
    `java-test-fixtures`
}

val sharedStarterRoot = rootProject.layout.projectDirectory
    .dir("hibernate-reactive-coroutines-spring-boot-starter-common/src")
val sharedStarterMain = sharedStarterRoot.dir("main")
val sharedStarterTest = sharedStarterRoot.dir("test")
val sharedStarterTestFixtures = sharedStarterRoot.dir("testFixtures")

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(sharedStarterMain.dir("kotlin"))
    }
    sourceSets.named("test") {
        kotlin.srcDir(sharedStarterTest.dir("kotlin"))
    }
    sourceSets.named("testFixtures") {
        kotlin.srcDir(sharedStarterTestFixtures.dir("kotlin"))
    }
}

sourceSets.named("main") {
    resources.srcDir(sharedStarterMain.dir("resources"))
}
sourceSets.named("test") {
    resources.srcDir(sharedStarterTest.dir("resources"))
}
sourceSets.named("testFixtures") {
    resources.srcDir(sharedStarterTestFixtures.dir("resources"))
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyStarterSourceLayout"))
}

dependencies {
    api(project(":hibernate-reactive-coroutines-core"))
    constraints {
        // Keep the Hibernate Reactive stack ahead of whatever the Spring Boot platform pins.
        api(lib("hibernate-orm-core"))
        api(lib("jakarta-persistence-api"))
    }

    // Required by the Vert.x pg-client at runtime for PostgreSQL SCRAM authentication.
    runtimeOnly(lib("scram-client"))

    // Compiled against by SslAwareSqlClientPoolConfiguration; provided at runtime by hibernate-reactive.
    compileOnly(lib("vertx-sql-client"))

    // Compiled against by the opt-in event-loop sharing auto-configuration; provided at runtime by
    // the application's WebFlux stack. Versions come from the Boot platform's reactor-bom import.
    compileOnly("org.springframework:spring-web")
    compileOnly("io.projectreactor.netty:reactor-netty-core")

    // Parameter-name extraction for @Query. Left versionless so the Kotlin plugin's own constraint
    // supplies the version, which is what the published POM records.
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Versions managed by the Spring Boot platform the applying module declares.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-tx")
    api("org.springframework.data:spring-data-commons")

    // Flow conversion.
    implementation(lib("kotlinx-coroutines-reactive"))
    implementation(lib("kotlinx-coroutines-reactor"))

    // Uni/Mono conversion.
    implementation(lib("mutiny-reactor"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boots a real Netty reactive web server in the event-loop sharing integration test.
    // application-test.yml pins web-application-type=none so other tests stay non-web.
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation(lib("kotest-runner-junit5"))
    testImplementation(lib("kotest-assertions-core"))
    testImplementation(lib("kotest-extensions-spring"))
    testImplementation(lib("mockk"))
    testImplementation(lib("scram-client"))

    testFixturesApi("org.springframework:spring-tx")
    testFixturesApi("org.springframework.data:spring-data-commons")
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi(lib("kotest-runner-junit5"))
    testFixturesApi(lib("kotest-assertions-core"))
    testFixturesApi(lib("kotest-extensions-spring"))
    testFixturesApi(lib("testcontainers-postgresql"))
    testFixturesApi(lib("vertx-pg-client"))
    testFixturesApi(lib("postgresql"))
    testFixturesApi(lib("scram-client"))
}

// Test fixtures are consumed inside this build only; keep their variants out of the publication.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

val pomFile = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication")
val moduleFile = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenPublication")

val verifyPublicationMetadata = tasks.register<VerifyPublicationMetadata>("verifyPublicationMetadata") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that test fixtures do not leak into published metadata."
    metadataFiles.from(pomFile.map { it.destination }, moduleFile.map { it.outputFile })
    forbiddenMarkers = TEST_FIXTURE_LEAK_MARKERS
}

tasks.named("check") {
    dependsOn(verifyPublicationMetadata)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

tasks.register<Test>("benchmark") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the performance benchmark tests excluded from `test`."

    // A bare Test task has no classpath and silently reports NO-SOURCE, so bind the test source set.
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform {
        includeTags("benchmark")
    }

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
