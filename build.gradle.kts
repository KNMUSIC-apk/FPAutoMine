// Addon FPAutoMine cho FakePlayerPlus.
//
// Trước khi build, cần cài API của FakePlayerPlus vào Maven local
// (chạy trong thư mục mã nguồn FakePlayerPlus):
//     ./gradlew :api:publishToMavenLocal
// Khi đó artifact là com.coderxi.plugin.fakeplayer:api:dev (phiên bản mặc định của repo).

plugins {
    java
}

group = "dev.fpaddon"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.coderxi.plugin.fakeplayer:api:dev")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("FPAutoMine")
    archiveVersion.set(project.version.toString())
}
