plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "de.erethon.rhea.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    implementation("de.erethon.rhea:Rhea:0.1.0-SNAPSHOT")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))

val rheaRoot = projectDir.resolve("../../..").canonicalFile
val compiledPage = layout.buildDirectory.dir("rhea")

val compileRhea by tasks.registering(Exec::class) {
    inputs.files(file("../rhea.json"), file("../index.html"), file("../guide.html"), file("../style.css"),
        file("../assets/AlegreyaSans-Regular.ttf"), file("../assets/OFL-AlegreyaSans.txt"),
        rheaRoot.resolve("compiler/compile.mjs"), rheaRoot.resolve("compiler/verify.mjs"))
    outputs.dir(compiledPage)
    commandLine("node", rheaRoot.resolve("compiler/rhea.mjs").absolutePath,
        "--source", file("..").absolutePath, "--output", compiledPage.get().asFile.absolutePath)
}

tasks.processResources {
    dependsOn(compileRhea)
    from(compiledPage.map { it.dir("templates") }) { into("rhea") }
}

val resourcePack by tasks.registering(Zip::class) {
    dependsOn(compileRhea)
    archiveFileName.set("rhea-example-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(compiledPage.map { it.dir("pack") })
    from(compiledPage.map { it.file("pack.mcmeta") })
}

tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar { archiveClassifier.set("") }
tasks.build { dependsOn(tasks.shadowJar, resourcePack) }
