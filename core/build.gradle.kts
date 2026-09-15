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
val generateHeaderTestFixture by tasks.registering(Exec::class) {
    // Always revalidate admission, including repository guardrails, before test use.
    workingDir(rootProject.projectDir)
    val resource = generatedHeaderResources.map {
        it.file("fixtures/synthetic-build-04.12.02-header-v1.bin")
    }
    outputs.file(resource)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", "-c",
        """
        import hashlib, pathlib, subprocess, sys
        from tools import validate_fixture_manifest as policy

        manifest = policy.load_json(policy.DEFAULT_MANIFEST)
        policy.validate(manifest, check_local_files=False)
        fixture = next(f for f in manifest["fixtures"] if f["id"] == "synthetic-build-04.12.02-header-v1")
        if fixture["ci"]["mode"] != "public" or fixture["artifact"]["storage"] != "generated":
            raise SystemExit("Header test fixture must be admitted for public generated use")
        artifact = fixture["artifact"]
        data = subprocess.check_output([sys.executable, artifact["generator"]["location"]])
        identity = artifact["identity"]
        if len(data) != identity["size_bytes"] or hashlib.sha256(data).hexdigest() != identity["sha256"]:
            raise SystemExit("Header test fixture identity mismatch")
        output = pathlib.Path(sys.argv[1])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        """.trimIndent(),
        resource.get().asFile.absolutePath,
    )
}

sourceSets.test {
    resources.srcDir(generatedHeaderResources)
}

tasks.processTestResources {
    dependsOn(generateHeaderTestFixture)
}
