plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

val generatedHeaderResources = layout.buildDirectory.dir("generated/headerTestResources")
val fixtureValidatorPython =
    providers.environmentVariable("FIXTURE_VALIDATOR_PYTHON").orElse("python3")

val materializeHeaderTestFixture by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val resource = generatedHeaderResources.map {
        it.file("fixtures/synthetic-build-04.12.02-header-v1.bin")
    }
    outputs.file(resource)
    outputs.upToDateWhen { false }
    doFirst {
        commandLine(
            fixtureValidatorPython.get(),
            "-B",
            "-c",
            """
            import pathlib, sys
            from tools import validate_fixture_manifest as policy

            data = policy.materialize_public_generated_fixture(
                "synthetic-build-04.12.02-header-v1",
            )
            output = pathlib.Path(sys.argv[1])
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            """.trimIndent(),
            resource.get().asFile.absolutePath,
        )
    }
}

sourceSets.test {
    resources.srcDir(generatedHeaderResources)
}

tasks.processTestResources {
    dependsOn(materializeHeaderTestFixture)
}
