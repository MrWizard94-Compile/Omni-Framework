// Shared configuration for every OmniLoader module.
//
// Two policies are enforced here rather than per-module, because both are
// constitutional rather than stylistic:
//
//  * Java 21 toolchain (TGT-1: Minecraft 1.21.1's requirement). Declared as a
//    *toolchain* so the build is reproducible on any machine regardless of
//    which JDK happens to be on PATH (SOUL §21/§33).
//  * Warnings are errors (SOUL §2, zero-tolerance). A loader manipulates
//    classloading and bytecode; a "harmless" warning here is tomorrow's
//    silent misbehaviour.

plugins {
    `java-library`
}

allprojects {
    group = "org.omniframework"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                "-Werror",
                // `this-escape` fires on legitimate final-class constructor
                // patterns and has no clean workaround in records; the rest of
                // -Xlint:all stays on. Documented suppression per SOUL §2.
                "-Xlint:-this-escape",
            ),
        )
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
