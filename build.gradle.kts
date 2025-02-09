group = "moe.kabii"
version = "deploy"

plugins {
    val kotlinVer = "1.5.10"
    kotlin("jvm") version kotlinVer
    kotlin("kapt") version kotlinVer
    application
    idea
}

repositories {
    mavenCentral()
    jcenter()
    maven {
        name = "jcenter-snapshot"
        url = uri("https://oss.jfrog.org/artifactory/libs-release")
    }
    maven {
        name = "jitpack.io"
        url = uri("https://jitpack.io")
    }
    maven {
        name = "sonatype-snapshots"
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    // reactor-kotlin-extensions
    maven {
        name = "spring.io-snapshots"
        url = uri("https://repo.spring.io/snapshot")
    }
    maven {
        name = "exposed-bintray"
        url = uri("https://dl.bintray.com/kotlin/exposed")
    }
    maven {
        name = "kotlinx-bintray"
        url = uri("https://kotlin.bintray.com/kotlinx")
    }
    maven {
        name = "lavaplayer"
        url = uri("https://m2.dv8tion.net/releases")
    }
}

dependencies {
    // kotlin
    api(kotlin("stdlib-jdk8"))
    api(kotlin("reflect"))

    // kotlin libs
    val coroutinesVer = "1.5.0"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVer")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVer")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.1.3") // can update once d4j 3.2 is available
    //implementation("io.projectreactor:reactor-core")

    implementation("moe.kabii:rusty-kotlin:3421f51") // custom functional style error handling

    implementation("com.discord4j:discord4j-core:3.2.0-M4") // discord websocket and api

    // music bot
    implementation("com.github.walkyst:lavaplayer-fork:1.3.95") // discord audio library
    implementation("com.github.natanbc:lavadsp:0.7.7") // some lavaplayer audio filters
    implementation("com.github.kokorin.jaffree:jaffree:2021.05.31")
    implementation("org.apache.commons:commons-compress:1.20")

    // other api - http calls
    implementation("com.squareup.okhttp3:okhttp:4.10.0-RC1")

    // other api - json response parsing
    val moshiVer = "1.12.0"
    implementation("com.squareup.moshi:moshi:$moshiVer")
    implementation("com.squareup.moshi:moshi-kotlin:$moshiVer")
    implementation("com.squareup.moshi:moshi-kotlin-codegen:$moshiVer")

    // emote parsing
    implementation("com.kcthota:emoji4j:6.0")

    // thumbnail file server
    val ktor = "1.4.1" // hold - 'blocking primitive' issue on latest
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-apache:$ktor")

    // ps2 websocket
    implementation("org.java-websocket:Java-WebSocket:1.5.1")
    implementation("com.googlecode.json-simple:json-simple:1.1.1")

    // welcome banner image processing
    val imageIO = "3.7.0"
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:$imageIO")
    implementation("com.twelvemonkeys.imageio:imageio-psd:$imageIO")
    implementation("com.twelvemonkeys.imageio:imageio-bmp:$imageIO")

    // database i/o
    // mongodb per-guild configurations
    implementation("org.litote.kmongo:kmongo-coroutine:4.2.7")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // postgresql user data, message history, tracked streams
    val exposedVer = "0.32.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVer")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVer")
    implementation("org.jetbrains.exposed:exposed-jodatime:$exposedVer")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVer")
    implementation("org.postgresql:postgresql:42.2.19")
    implementation("com.zaxxer:HikariCP:5.0.0")

    // language detection
    implementation("com.github.pemistahl:lingua:1.1.0")

    // .toml token configuration
    implementation("com.uchuhimo:konf:1.1.2")

    // logging
    // HOLD VERSION - alpha5 breaks file output (and has for over a year)
    implementation("ch.qos.logback:logback-classic:1.3.0-alpha4")

    // other
    implementation("commons-validator:commons-validator:1.7")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.apache.commons:commons-text:1.9")

    implementation("org.reflections:reflections:0.9.12") // command detection and registration

    // youtube xml parsing
    // https://github.com/gradle/gradle/issues/13656#issuecomment-658873625
    implementation("org.dom4j:dom4j:2.1.3")
    components {
        withModule("org.dom4j:dom4j") {
            allVariants { withDependencies { clear() } }
        }
    }
}

val updateVersion = task("updateVersion") {
    // custom script to create version file name and increment build number
    val versionsFile = file("build.version")
    val versions = versionsFile.readLines()
    val (major, minor, build, flag) = versions.map { line -> line.substring(line.indexOf(':') + 1, line.length).trim() }
    //val buildFlag = if(flag.isNotBlank()) "-$flag" else ""
    val buildCount = build.toInt() + 1

    versionsFile.bufferedWriter().use { output ->
        output.write("major: $major\n")
        output.write("minor: $minor\n")
        output.write("build: $buildCount\n")
        output.write("flag: $flag")
    }
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "14"
    }
    java.targetCompatibility = JavaVersion.VERSION_15

    build {
        dependsOn(updateVersion)
    }

    jar {
        // include build version in jar for bot self-info command
        from(".") {
            include("build.version")
        }
    }

    // credit to https://github.com/gradle/gradle/issues/1989#issuecomment-550192866
    named<CreateStartScripts>("startScripts") {
        doLast {
            windowsScript.writeText(windowsScript.readText().replace(Regex("set CLASSPATH=.*"), "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"))
        }
    }
}

application {
    mainClass.set("moe.kabii.FBKKt")
    applicationDefaultJvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}