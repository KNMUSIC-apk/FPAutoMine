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

// FakePlayerPlus được build bằng JDK 25 (class version 69), nên addon cũng dùng JDK 25
// và release 25. Server phải chạy Java 25 để cả FakePlayerPlus lẫn addon chạy được.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
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
