plugins {
    id("java")
    id("eclipse")
    id("org.sonarqube") version "6.0.1.5171"
}

allprojects {
    apply(plugin = "eclipse")
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

val resolvedSonarToken: String = System.getenv("SONAR_TOKEN")
    ?: System.getenv("SONAR_IO_TOKEN")
    ?: (project.findProperty("sonar.token") as? String)
    ?: run {
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines()
                .firstOrNull { it.trim().startsWith("SONAR_TOKEN=") || it.trim().startsWith("SONAR_IO_TOKEN=") }
                ?.substringAfter("=")
                ?.trim()
        } else null
    }
    ?: ""

val resolvedSonarOrg: String = System.getenv("SONAR_ORGANIZATION")
    ?: run {
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines()
                .firstOrNull { it.trim().startsWith("SONAR_ORGANIZATION=") }
                ?.substringAfter("=")
                ?.trim()
        } else null
    }
    ?: "skoczo-github"

sonar {
    properties {
        property("sonar.host.url", System.getenv("SONAR_HOST_URL") ?: "https://sonarcloud.io")
        property("sonar.organization", resolvedSonarOrg)
        property("sonar.projectKey", System.getenv("SONAR_PROJECT_KEY") ?: "skoczo_LanAlmanac")
        property("sonar.projectName", "GreatNetworkManager (LanAlmanac)")
        property("sonar.token", resolvedSonarToken)
        property("sonar.sources", "gnm-app/src/main/java,frontend/src")
        property("sonar.tests", "gnm-app/src/test/java")
        property("sonar.java.binaries", "gnm-app/build/classes/java/main")
        property("sonar.coverage.jacoco.xmlReportPaths", "gnm-app/build/jacoco-report/jacoco.xml,gnm-app/build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/lcov.info")
        property("sonar.exclusions", "**/node_modules/**,**/build/**,**/dist/**,**/test-results/**")
    }
}

tasks.register("detectBackendIssues") {
    group = "verification"
    description = "Scans backend Java code using SonarCloud (and Javac compiler warnings)."
    dependsOn(":gnm-app:compileJava")
    if (resolvedSonarToken.isNotBlank()) {
        dependsOn("sonar")
    }
    
    doLast {
        println("\n==================================================")
        println("       BACKEND ISSUE SCANNER (SonarCloud / Javac) ")
        println("==================================================")
        
        if (resolvedSonarToken.isNotBlank()) {
            println("--> SONAR_TOKEN detected. SonarCloud static analysis executed successfully.")
        } else {
            println("NOTE: SONAR_TOKEN is not set. Local Java compilation warnings checked.")
            println("To send report to SonarCloud, export SONAR_TOKEN=<your-token> or run:")
            println("  ./gradlew sonar -Dsonar.token=<your-token>")
        }
        
        println("--------------------------------------------------")
        println("SUCCESS: Backend build and verification completed!")
        println("==================================================\n")
    }
}

tasks.register("detectFrontendIssues", Exec::class) {
    group = "verification"
    description = "Scans frontend (React/TypeScript) code for type errors, linter issues, and unused exports."
    workingDir = file("frontend")
    
    commandLine("bash", "-c", """
        echo ""
        echo "=================================================="
        echo "       FRONTEND ISSUE & DEAD CODE SCANNER         "
        echo "=================================================="
        echo "--> Running TypeScript type check..."
        npx tsc -b || true
        
        echo ""
        echo "--> Running ESLint check..."
        npx eslint . || true
        
        echo ""
        echo "--> Running Unused File / Dead Code Scanner..."
        npx -y unimported || true
        
        echo "=================================================="
        echo "Frontend scan completed!"
        echo "=================================================="
        echo ""
    """.trimIndent())
}

tasks.register("detectAllIssues") {
    group = "verification"
    description = "Runs issue detection scans on both backend and frontend."
    dependsOn("detectBackendIssues", "detectFrontendIssues")
}


