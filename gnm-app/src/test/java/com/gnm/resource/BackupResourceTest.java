package com.gnm.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class BackupResourceTest {

    @Test
    public void testDownloadBackupUnauthorized() {
        given()
          .when()
          .get("/api/backup/download?password=testpass")
          .then()
          .statusCode(401);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "gnm-viewer")
    public void testDownloadBackupForbiddenForViewer() {
        given()
          .when()
          .get("/api/backup/download?password=testpass")
          .then()
          .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testDownloadBackupMissingPassword() {
        given()
          .when()
          .get("/api/backup/download")
          .then()
          .statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testDownloadBackupSuccess() {
        given()
          .when()
          .get("/api/backup/download?password=testpassword123")
          .then()
          .statusCode(200);
    }
}
