# HKUST Marketplace

A JavaFX desktop application backed by a local MySQL database.

## Prerequisites

- A desktop session for the JavaFX window.
- A full JDK 21 for the reference setup. Maven compiles for Java 21. The full test suite has also been verified on JDK 26.0.2.1. Run `.\mvnw.cmd -v` to check which JDK Maven is using.
- Internet access for the first build. The Maven Wrapper downloads Maven and the project dependencies, including JavaFX and the MySQL driver.
- MySQL Server 8 running on `localhost:3306`, and a SQL client for one-time setup. The examples below use the MySQL command-line client on your PATH.

## Set up MySQL once

Open PowerShell in the folder containing `pom.xml` and `mvnw.cmd`. Connect with a MySQL account that can create databases, tables, and users:

```powershell
mysql -h localhost -P 3306 -u root -p
```

At the MySQL prompt, run the following commands. Replace the example password with your own. The schema creates the tables and seeds the demo logins listed below.

```sql
CREATE DATABASE IF NOT EXISTS hkust_marketplace;
SOURCE src/main/resources/db/schema.sql
CREATE USER 'marketplace_app'@'localhost' IDENTIFIED BY 'replace-with-your-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON hkust_marketplace.* TO 'marketplace_app'@'localhost';
exit
```

Use a fresh database for a new setup. The schema script creates missing tables and leaves existing table definitions in place. On older databases, check that the `deals.buyer_username` and `deals.seller_username` columns allow `NULL` and that both foreign keys to `users` use `ON DELETE SET NULL`. This preserves deal history when an account is deleted.

In the PowerShell session that will launch the app, set the MySQL credentials:

```powershell
$env:MARKETPLACE_DB_USER = 'marketplace_app'
$env:MARKETPLACE_DB_PASSWORD = 'replace-with-your-password'
```

For persistent settings, create `%USERPROFILE%\.hkust-marketplace\database.properties` with the following contents. A complete pair of environment variables takes precedence over this file.

```properties
username=marketplace_app
password=replace-with-your-password
```

Keep your MySQL credentials and local configuration files outside the repository.

## How to Run (Windows)

Open **PowerShell** or **Command Prompt** in the folder containing `pom.xml` and `mvnw.cmd`:

```powershell
.\mvnw.cmd clean javafx:run
```

First run downloads Maven automatically (needs internet). Then the app window should open.

### Alternative: if you already installed Maven and added it to PATH

```powershell
mvn clean javafx:run
```

### Run from IntelliJ IDEA

1. Open the project folder as a Maven project
2. Wait for dependencies to download
3. Open `MarketplaceApp.java` → right-click → **Run**

### Run from VS Code

1. Install **Extension Pack for Java**
2. Open the folder
3. Run `MarketplaceApp.java`

## Demo Credentials

| Role   | Username  | Password   |
|--------|-----------|------------|
| Admin  | `admin`   | `admin123` |
| Buyer  | `alice`   | `password1`|
| Buyer  | `bob`     | `password1`|
| Seller | `charlie` | `password1`|
| Seller | `diana`   | `password1`|

## Run the tests and generate coverage

Run the suite from a desktop session so the JavaFX tests can open and dismiss windows. The Admin suites use the database configured for the test run. Use a fresh MySQL database initialized with `src/main/resources/db/schema.sql` so they can safely create and delete test records. Other MySQL integration fixtures start disposable servers and locate `mysqld` in the standard Windows installation path or on `PATH`. If MySQL is installed elsewhere, set `MYSQLD` to the full executable path before testing.

To keep test data separate from your app data, create a database such as `hkust_marketplace_test`. Import a copy of `schema.sql` with its first line changed to `USE hkust_marketplace_test;` and give your test MySQL account `SELECT`, `INSERT`, `UPDATE`, and `DELETE` privileges on that database. Save the connection details to a properties file outside the repository:

```properties
url=jdbc:mysql://localhost:3306/hkust_marketplace_test
username=your-test-mysql-user
password=your-test-mysql-password
```

From the folder containing `pom.xml`, run this PowerShell command with the path to that file:

```powershell
.\mvnw.cmd "-Dmarketplace.db.config=C:/path/to/test-db.properties" "-Dtest=*Test,*IT" clean test jacoco:report
```

The database override applies to that Maven run. If your normal app database is already a fresh test database, you can omit the override and use the credentials configured above. The test pattern includes both regular JUnit tests and `*IT` integration tests.

| Report | Output |
|--------|--------|
| JUnit results | `target/surefire-reports/` contains XML and text reports. Maven prints totals for tests run, failures, errors, and skips. |
| JaCoCo coverage | Open `target/site/jacoco/index.html` and read **Branches** in the **Total** row for project-wide branch coverage. |

The assignment asks for a JUnit report with 100% of tests passing and a coverage report with more than 65% branch coverage.

## Features

### Shared
- Welcome screen with role selection (Buyer / Seller / Admin)
- HKUST branding (logo + colour palette: deep blue `#003974` + gold `#A68137`)
- Modern card-based UI, icons (Ikonli), consistent sidebars & top bars

### Buyer Portal
- Registration, Login, Browse Marketplace, Make Offer / Borrow / Claim
- My Deals, Deal History, Notifications, Profile

### Seller Portal
- Registration, Login, Create Listing, My Listings
- Seller Stats (charts), Reviews & Feedback, Notifications, Profile

### Admin Portal
- Login only (seeded), Listing Review (approve/reject)
- Manage Users, All Listings, All Deals, Seller Ratings, Notifications, Profile

## Project Structure

```
HKUST-Marketplace/
├── mvnw / mvnw.cmd          ← Maven Wrapper (use these!)
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/hkust/marketplace/
    │   ├── MarketplaceApp.java
    │   ├── controllers/
    │   ├── models/
    │   └── utils/
    └── resources/
        ├── fxml/
        ├── css/styles.css
        └── images/hkust_logo.png
```

## Troubleshooting

### `mvn` is not recognized
Use the wrapper instead:
```powershell
.\mvnw.cmd clean javafx:run
```

### `java` / `javac` not recognized
Install JDK 21 from https://adoptium.net/ and **restart the terminal**.
Make sure the installer option “Add to PATH” was checked.

### Build fails with JavaFX / DISPLAY errors
You need a normal desktop session (not a headless server). On Windows this is usually fine.

### To see the real error
```powershell
.\mvnw.cmd clean javafx:run -e
```
Copy the lines that start with `Exception in thread` or `Caused by`.

## Licence

Educational use — HKUST COMP project skeleton.
