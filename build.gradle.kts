import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    application
    jacoco
    id("com.diffplug.spotless") version "8.6.0"
    id("com.gradleup.shadow") version "9.4.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

application {
    mainClass.set("com.etfarb.cli.Main")
}

tasks.named<Jar>("shadowJar") {
    archiveBaseName.set("etf-arb")
    archiveClassifier.set("all")
    archiveVersion.set("")
}

tasks.register<JavaExec>("scanSample") {
    group = "verification"
    description = "Scans the bundled sample snapshot and checks the report against the expected one."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.etfarb.cli.Main")
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })

    val sampleDir = layout.projectDirectory.dir("src/test/resources/sample")
    val actual = layout.buildDirectory.file("sample/report-actual.csv")
    doFirst {
        actual.get().asFile.parentFile.mkdirs()
        args = listOf(
            "--quotes", sampleDir.file("quotes.csv").asFile.path,
            "--baskets", sampleDir.file("baskets.csv").asFile.path,
            "--out", actual.get().asFile.path,
            "--max-quote-age", "5",
        )
    }
    doLast {
        val expected = sampleDir.file("expected-report.csv").asFile.readText().trimEnd()
        val produced = actual.get().asFile.readText().trimEnd()
        if (produced != expected) {
            throw GradleException(
                "sample report mismatch\n--- expected ---\n$expected\n--- actual ---\n$produced")
        }
    }
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures scan throughput on a generated universe."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.etfarb.bench.ScanBenchmark")
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

spotless {
    java {
        googleJavaFormat("1.22.0")
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.check {
    dependsOn(tasks.named("spotlessCheck"))
    dependsOn(tasks.named("jacocoTestReport"))
}
