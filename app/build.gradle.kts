plugins {
    id("application")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.temporal:temporal-sdk:1.33.0")
    testImplementation("io.temporal:temporal-testing:1.33.0")
}

application {
    mainClass.set("emailworkflow.EmailStarter")
}

tasks.register<JavaExec>("runWorker") {
    group = "application"
    description = "Run the Temporal email worker"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("emailworkflow.EmailWorker")
}

tasks.register<JavaExec>("runStarter") {
    group = "application"
    description = "Start the email workflow"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("emailworkflow.EmailStarter")
}
