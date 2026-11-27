plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.controlx.server.ServerKt")
}
