# HKUST Marketplace

A JavaFX desktop application backed by a local MySQL database.

## Prerequisites

- A desktop session for the JavaFX window.
- A full JDK 21. Check that both `java -version` and `javac -version` report version 21 in your terminal.
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

## How to Run (Windows)

Open **PowerShell** or **Command Prompt** in the project folder  
(`...\HKUST-Marketplace\HKUST-Marketplace`):

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
