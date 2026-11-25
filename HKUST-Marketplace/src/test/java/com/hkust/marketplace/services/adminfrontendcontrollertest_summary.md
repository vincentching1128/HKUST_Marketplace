# Test Case Summary — `AdminFrontendControllerTest.java` (JavaFX Frontend Controller Suite)

## Overview

* **Target File:** `AdminFrontendControllerTest.java`
* **Test Scope:** Real JavaFX UI and controller testing of **`AdminLoginController.java`** (`AdminLoginView.fxml`) and **`AdminDashboardController.java`** (`AdminDashboardView.fxml`), including scene navigation, form controls, `TableView` rendering, input validation, modal dialogs, and end-to-end persistence to MySQL.
* **Total Automated Tests:** **31 JavaFX UI `@Test` methods** organized into **8 `@Nested` classes**. The class has a 120-second JUnit timeout so a missed dialog fails instead of hanging the whole run.
* **Last verified result:** `mvnw.cmd test -Dtest=AdminFrontendControllerTest -DfailIfNoTests=false` → **31 tests, 0 failures, 0 errors, 0 skipped** (2026-11-25).
* **UI Automation Mechanism:** Loads real FXML views onto a JavaFX `Stage` on the FX Application Thread and runs a lightweight `Timeline` dialog watcher that handles modal `Alert`, `TextInputDialog`, and custom `Dialog` windows without blocking or deadlocking. Dialog actions are deferred with `Platform.runLater(...)` so nested `showAndWait()` alerts are not opened during a JavaFX animation/layout pulse.
* **Database Safety & Isolation:** Uses a per-test prefix (`fx<8-hex>_`) and deletes all test-created users, listings (including those created through the UI form), deals, reviews, and notifications in `@AfterEach`.

---

## Coverage Breakdown by Controller & Screen

### 1. Admin Login UI (`AdminLoginControllerTests` — 3 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-LOGIN-01** | `loginScreenLayoutAndGoWelcome` | Verifies `AdminLoginView.fxml` is login-only (no registration buttons or links), `errorLabel` starts hidden, and clicking `"← Back to Welcome"` navigates back to `WelcomeView`. |
| **TC-UI-LOGIN-02** | `loginErrorStatesDisplayCorrectMessages` | Fires `"Login as Admin"` with invalid inputs and verifies the exact inline `errorLabel` messages for blank credentials, non-existent user, wrong password, Buyer/Seller role mismatch, and deactivated admin account. |
| **TC-UI-LOGIN-03** | `validLoginOpensAdminDashboard` | Enters trimmed valid admin credentials, clicks `"Login as Admin"`, verifies `SessionManager` stores the admin, and confirms the stage transitions to `AdminDashboardView` with the admin's full name and default Review screen. |

---

### 2. Listing Review & Logout UI (`DashboardReviewAndLogoutTests` — 5 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-REV-01** | `reviewScreenDisplaysPendingCardDetailsAndPhotos` | Verifies `initialize()` and `showReview()` render pending cards with title, `PENDING REVIEW` badge, `@sellerUsername`, seller full name, category, listing type (`For Sale`), meet-up location, formatted submitted date, and a decoded PNG `ImageView` thumbnail. |
| **TC-UI-REV-02** | `reviewCardBranchesAndShortenHelper` | Exercises `createPendingCard()` styling branches (`LIVE`, `REJECTED`, `SOLD`), `"—"` fallbacks for missing values, `"No photos uploaded."` & unreadable photo skipping, and `shortenForNotification()` truncation (> 500 chars -> `"..."`). |
| **TC-UI-REV-03** | `approveButtonFlow` | Tests clicking **Approve** -> **Cancel** (card stays pending) and **Approve** -> **OK** (persists `LIVE`, sends `"Listing Approved"` notification to seller, removes card from queue). |
| **TC-UI-REV-04** | `rejectButtonFlow` | Tests clicking **Reject** with a blank reason (shows `"Reason is required."` warning and keeps card), then with a written reason (persists `REJECTED` + reason, sends `"Listing Rejected"` notification, removes card). |
| **TC-UI-REV-05** | `staleReviewWarningAndLogout` | Simulates a seller editing a listing after the admin opened the queue, verifies clicking **Approve** displays the stale-conflict warning and reloads the updated card, and confirms clicking **Logout** clears `SessionManager` and returns to `WelcomeView`. |

---

### 3. Manage All Listings UI (`ManageAllListingsUITests` — 3 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-LST-01** | `createListingFormValidationAndSubmit` | Opens **Create Listing** and verifies: active-sellers-only dropdown (excludes inactive sellers & buyers), type-first form gating (fields disabled until type chosen), switching `Sell` -> `Lend` clears price, aggregated validation warnings (negative price, > 2 decimal places, 0 lending duration), and successful creation of a `Lend` listing that appears in **All Listings**. |
| **TC-UI-LST-02** | `editListingFormAndPhotoPreviews` | Clicks **Edit** on a live listing with 2 photos, verifies the read-only seller label, pre-filled fields and `"2 of 6 photos selected"`, clicks per-photo **Remove** (`"1 of 6"`), clicks **Clear Photos** (`"0 of 6"`), confirms save (`"The listing will return to Pending Review."`), and verifies DB updates to `PENDING_REVIEW` with 0 photos. |
| **TC-UI-LST-03** | `removeListingUiFlow` | Clicks **Remove** -> **OK** and verifies blank reason shows `"Reason is required."`, valid reason soft-deletes the listing (`"Listing removed."`) and removes the row from UI, and calling `removeListing` again shows the already-removed warning. |

---

### 4. Manage All Users UI (`ManageUsersUITests` — 3 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-USR-01** | `showUsersAndToggleActive` | Opens **Manage Users** and confirms `ADMIN` accounts are filtered out, each Buyer/Seller row exposes **Edit**/**Delete**, and clicking **Deactivate** / **Activate** updates MySQL, status text (`Deactivated` / `Active`), and button labels. |
| **TC-UI-USR-02** | `editUserDialogValidationAndSave` | Opens the modal **Edit User** dialog for a Buyer (verifies blank Full Name triggers a warning while keeping the dialog open, then saves new name + `Hall 8`), edits a Seller (saves name, bio, and `"None"` preferred meet-up -> `NULL`), and tests the missing-user warning when a target no longer exists. |
| **TC-UI-USR-03** | `deleteUserUiFlow` | Clicks **Delete** on a seller, confirms the permanent-deletion dialog, verifies the success alert, confirms the user row disappears from UI & MySQL, and verifies the seller's listings are marked `REMOVED`. |

---

### 5. All Deals Record UI (`AllDealsUITests` — 1 test)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-DLS-01** | `showDealsFiltersAndFormatting` | Opens **All Deals**, verifies all 8 `TableView<Deal>` columns (including **Agreed terms**), searches by keyword, filters by Deal Type (`Bought`, `Borrowed`), checks cell formatting including `"(deleted user)"` for a permanently deleted buyer and `"—"` for unset return/complete dates, and verifies **Reset** clears filters. |

---

### 6. Seller Ratings Overview UI (`SellerRatingsUITests` — 1 test)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-RAT-01** | `showRatingsFilterValidationAndHide` | Opens **Seller Ratings**, tests invalid `yyyy-MM-dd` and inverted date-range error labels, filters by seller + score (`2 stars`) + date, clicks **Hide** and verifies `(hidden)` while preserving active filters, clicks the enabled **Unhide** button and verifies `(hidden)` disappears and `hidden` is `FALSE` in MySQL, verifies **Reset** clears all filters, and checks the missing-review warning in `setReviewVisibility()`. |

---

### 7. Notifications & My Profile UI (`NotificationsAndProfileUITests` — 2 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-NTF-01** | `showNotificationsAndSessionGuard` | Opens **Notifications**, verifies `NotificationBoard` mounts and asynchronously renders the admin's unread DB notification, confirms switching tabs deactivates the board, and checks that a null session redirects to `AdminLoginView`. |
| **TC-UI-PRF-01** | `showProfileValidationAndSave` | Opens **My Profile**, verifies warnings for blank Full Name, > 100-character Full Name, and weak password, then saves a valid Full Name + strong password, confirms the top-bar `welcomeLabel` updates and `PasswordField` clears, verifies login works with the new password, and checks null-session redirect to `AdminLoginView`. |

---

## Additional Required / Edge Coverage (`AdditionalFrontendCoverageTests` — 13 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-UI-REV-06** | `rejectCancelLeavesPending` | **Reject** -> **Cancel** on the reason dialog leaves the listing `PENDING_REVIEW` and sends no rejection notification. |
| **TC-UI-REV-07** | `staleRejectConflictShowsWarning` | Seller edits mid-review; **Reject** shows the stale conflict warning, reloads the edited card, and sends no rejection notification. |
| **TC-UI-REV-08** | `lendGiveawayCardsAndSellerProfileButton` | LEND/GIVEAWAY cards render `For Lending`/`Giveaway` and six `ImageView` thumbnails; Seller Profile button exists on Review/All Listings/Seller rows (not Buyer rows) and the reusable trigger opens/closes safely. |
| **TC-UI-USR-04** | `userDeleteAndEditCancelLeaveDataUntouched` | Cancelling Delete and Edit keeps the user, listing, and deal untouched in DB/UI. |
| **TC-UI-USR-05** | `userValidationAndNullableResidence` | Full Name >100 and Bio >65,535 bytes warnings; Buyer Residence Hall `None` persists NULL. |
| **TC-UI-USR-06** | `userActionsPersistAuditNotifications` | UI Deactivate/Activate/Edit/Delete persist `Account Deactivated`, `Account Activated`, `User Account Updated`, `User Account Deleted` audit notifications. |
| **TC-UI-LST-04** | `adminCreatesSellGiveawayAndLendSeconds` | UI creates SELL (12.34), LEND (`LendingUnit.SECONDS`), and GIVEAWAY (price/duration cleared); validates >max/non-numeric price and non-integer duration; form Cancel returns to All Listings. |
| **TC-UI-LST-05** | `listingEditAndRemoveCancelPaths` | Edit -> Save -> Cancel on confirmation leaves DB unchanged; ACCEPTED deal blocks save with warning; Remove confirmation Cancel and Removal-Reason Cancel both leave the listing intact. |
| **TC-UI-DLS-02** | `dealsGiveawayCompletedAndReturnedFormatting` | Filters `Received` (GIVEAWAY); COMPLETED/RETURNED deals render formatted `dd MMM yyyy HH:mm` start/end dates. |
| **TC-UI-DLS-03** | `dealsDeletedParticipantsAndEmptyState` | Deleted seller renders `(deleted user)`; a both-deleted deal is hidden from All Deals; a non-matching search shows the placeholder and `0 deals`. |
| **TC-UI-NTF-02** | `notificationMarkReadAndSecondAdminIsolation` | **Mark as read** persists `isRead=TRUE`, Show-read toggling retains the item, and a second admin sees only their own notifications. |
| **TC-UI-PRF-02** | `profileNoPartialSaveAuditAndDeletedSession` | Weak password does not update name/label; name-only save creates `Admin Profile Updated`; deleting the session row redirects `showProfile()` to Admin Login. |
| **TC-UI-RAT-03** | `ratingsFlaggedDatesAndEmptyState` | Flagged badge (`FLAGGED`), invalid To-date error, one-sided From/To date filters, and empty-result message `No reviews match these filters.` |

### Known production gaps / notes

- `SellerProfilePane.profileButton(...)` assumes its button is already attached to a scene; the test uses the safe trigger (button added to the attached content pane) and separately verifies row placement.
- `ReviewRepository.hide(String, Boolean)` still uses inverted semantics (`false` hides, `true` unhides); tests reflect the current implementation.
- AllDeals now has **8** columns because production added **Agreed terms**; the tests were updated for the new column order.
