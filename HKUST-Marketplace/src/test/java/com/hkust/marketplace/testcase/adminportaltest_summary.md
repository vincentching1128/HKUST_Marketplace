# Test Case Summary — `AdminPortalTest.java` (Backend & Integration Suite)

## Overview

* **Target File:** `AdminPortalTest.java`
* **Test Scope:** End-to-end backend, service, and MySQL repository functionality for **Task 3: Admin Portal** (`AuthService`, `ListingService`, `NotificationService`, `BuyerListingService`, `UserRepository`, `ListingRepository`, `DealRepository`, `NotificationRepository`, `ReviewRepository`, `ChatRepository`, and `SellerStatsRepository`).
* **Total Automated Tests:** **50 JUnit 5 `@Test` methods** organized into **9 `@Nested` classes** (Tasks 3.1–3.8 plus the additional edge-coverage suite).
* **Last verified result:** `mvnw.cmd test -Dtest=AdminPortalTest -DfailIfNoTests=false` → **50 tests, 0 failures, 0 errors, 0 skipped** (2026-11-26).
* **Database Safety & Isolation:** Every test generates a unique per-test prefix (`t<8-hex>_`) for temporary users, listings, deals, reviews, chat threads, and notifications, and removes them in `@AfterEach` in foreign-key-safe order. Seeded accounts (`admin`, `alice`, `bob`, `charlie`, `diana`) remain untouched.

---

## Coverage Breakdown by Subtask

### Task 3.1 — Admin Login (`Task31_AdminLoginTests` — 6 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-ADM-01** | `seededAdminCanLogin` | Confirms the hardcoded seeded credential (`admin` / `Admin123`) authenticates with `LoginStatus.SUCCESS` and role `ADMIN` (safely skipped via JUnit `Assumptions` if the seeded password was customized). |
| **TC-ADM-02** | `adminLoginTrimsAndLowercasesUsername` | Verifies login trims leading/trailing whitespace and normalizes username casing before authentication. |
| **TC-ADM-03** | `blankCredentialsFailValidation` | Ensures blank usernames, empty passwords, and null roles are rejected with `LoginStatus.INVALID_INPUT`. |
| **TC-ADM-04** | `unknownUserAndWrongPasswordRejected` | Confirms non-existent accounts return `USER_NOT_FOUND` and wrong passwords return `INCORRECT_PASSWORD` with a null user object. |
| **TC-ADM-05** | `nonAdminRolesRejectedOnAdminPortal` | Verifies active `BUYER` and `SELLER` accounts are blocked from logging into the Admin portal (`LoginStatus.WRONG_ROLE`). |
| **TC-ADM-06** | `deactivatedAdminCannotLogin` | Verifies a deactivated admin account (`active = FALSE`) is blocked with `LoginStatus.ACCOUNT_DEACTIVATED`. |

---

### Task 3.2 — Listing Review & Approval Screen (`Task32_ListingReviewTests` — 4 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-REV-01** | `pendingQueueReturnsCompleteFieldsFifo` | Confirms `findByStatus(PENDING_REVIEW)` returns submissions in FIFO order (`posted_at ASC, id ASC`) with all required fields (title, seller username/full name, category, listing type, meet-up location, submitted date, status, and photo bytes). |
| **TC-REV-02** | `approvePendingListingUpdatesStatusAndVersion` | Verifies approving a pending listing sets status to `LIVE`, populates `reviewed_at`, clears `rejection_reason`, increments `row_version` (`1 -> 2`), and removes the item from the review queue. |
| **TC-REV-03** | `rejectPendingListingRequiresAndStoresReason` | Ensures blank/null rejection reasons throw `IllegalArgumentException`, while a valid written reason persists `REJECTED`, stores the trimmed reason, stamps `reviewed_at`, and increments `row_version`. |
| **TC-REV-04** | `staleApprovalRejectedByRowVersion` | Tests optimistic locking: if a seller edits a listing after the admin loaded the queue (bumping `row_version`), the admin's stale approval returns `false` and leaves the seller's new content in `PENDING_REVIEW`. |

---

### Task 3.3 — Manage All Users Screen (`Task33_ManageUsersTests` — 7 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-USR-01** | `findAllReturnsPersistedUsers` | Confirms `userRepository.findAll()` loads persisted accounts from MySQL ordered alphabetically by username. |
| **TC-USR-02** | `adminCanEditBuyerAndSellerProfiles` | Verifies editing Buyer fields (Full Name, Residence Hall) and Seller fields (Full Name, Bio, and clearing optional Preferred Meet-up to `NULL`) persists properly without overwriting unrelated attributes. |
| **TC-USR-03** | `deactivateAndReactivateUser` | Verifies `setActive(false/true)` updates MySQL, blocks login (`ACCOUNT_DEACTIVATED`) while inactive, restores login when reactivated, and throws `IllegalArgumentException` for missing usernames. |
| **TC-USR-04** | `permanentDeletionCascadesAndPreservesHistoryCorrectly` | Validates the complete permanent deletion policy across both participants: deleting the first user removes their account & notifications, cancels open `PENDING` deals while preserving `COMPLETED` deals, sets `deleted = 1` on deals & chats (keeping them visible to the remaining user/admin); deleting the second user soft-deletes their listings as `REMOVED` (retaining photos with `seller_username = NULL`), advances deals & chats to `deleted = 2` (hiding them from default queries); retrying deletion is idempotent and throws `IllegalArgumentException`. |
| **TC-USR-05** | `adminAccountsProtectedFromDeletion` | Confirms `ADMIN` accounts cannot be permanently deleted and non-admin callers are blocked from calling `deleteUserAsAdmin`. |
| **TC-USR-08** | `sellerDeletionNotifiesBuyersWithActiveDeals` | Verifies that deleting a seller creates an `"Account"` notification for the buyer of each `PENDING`/`COUNTERED`/`ACCEPTED` deal, while a buyer whose deal was already `COMPLETED` receives no deletion notification; the active deal becomes `CANCELLED` and the completed deal remains `COMPLETED`. |
| **TC-USR-09** | `deactivatedAdminCannotDeleteUsers` | Deactivates an admin and confirms `deleteUserAsAdmin` rejects it, leaving the target account intact. |

---

### Task 3.4 — Manage Own Profile Screen (`Task34_ManageOwnProfileTests` — 3 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-PRF-01** | `adminUpdatesFullNameOnly` | Verifies updating only Full Name (with blank new password) updates `full_name` in MySQL while keeping the existing password hash and login working. |
| **TC-PRF-02** | `adminUpdatesPasswordSuccessfully` | Verifies changing the password stores a new Argon2id hash, invalidates the old password, and allows logging in with the new password. |
| **TC-PRF-03** | `weakPasswordsRejected` | Confirms weak passwords (< 8 characters, missing uppercase, missing lowercase, or missing digit) throw `IllegalArgumentException` without altering the stored password. |

---

### Task 3.5 — All Deals Record Screen (`Task35_AllDealsRecordTests` — 2 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-DLS-01** | `searchDealsFiltersByTypeAndKeyword` | Verifies `dealRepository.searchDeals` retrieves `SELL` (Bought), `LEND` (Borrowed), and `GIVEAWAY` (Received) deals with `COMPLETED` / `RETURNED` / `borrowStart` timestamps, and filters accurately by deal type and keyword (item title, buyer username, or seller username). |
| **TC-DLS-02** | `searchDealsRejectsInvertedDateRange` | Ensures searching with a start date after the end date throws `IllegalArgumentException`. |

---

### Task 3.6 — Notification Board (`Task36_NotificationBoardTests` — 4 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-NTF-01** | `sellerListingSubmissionNotifiesAdmins` | Verifies a seller submitting a new listing automatically sends active admins a timestamped, unread notification categorized as `"New Listing"` (`"New Listing Submitted"`). |
| **TC-NTF-02** | `notifyAllAdminsTargetsOnlyActiveAdmins` | Confirms `notifyAllAdmins` delivers `"Account"` audit alerts exclusively to active admins and skips deactivated admins. |
| **TC-NTF-03** | `reportedListingNotifiesAdmins` | Verifies a buyer reporting a live listing via `buyerListingService.reportListing` generates a timestamped `"Listing Report"` (`"Listing Reported"`) moderation alert for admins containing the report reason. |
| **TC-NTF-04** | `markAsReadAndRecipientIsolation` | Confirms `markAsRead` sets `isRead = TRUE` once, blocks marking another user's notification as read, hides read items from the unread view, and displays them when full history (`includeRead = true`) is requested. |

---

### Task 3.7 — Manage All Listings Screen (`Task37_ManageAllListingsTests` — 7 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-LST-01** | `adminCreatesListingOnBehalfOfActiveSeller` | Verifies an admin can create a listing owned by an active seller (starting as `PENDING_REVIEW`), notifies other active admins while excluding the acting admin, and rejects deactivated sellers or buyer accounts. |
| **TC-LST-02** | `listingValidationRejectsInvalidFields` | Ensures negative prices, prices with > 2 decimal places, non-positive lending durations (`0`), and listings with > 6 photos throw `IllegalArgumentException`. |
| **TC-LST-03** | `adminEditCancelsOpenOffersAndNotifiesWithOldTitle` | Verifies editing a live listing atomically updates content, resets status to `PENDING_REVIEW`, cancels open `PENDING` proposals, and notifies both buyers (`"Listing Changed"`) and the seller (`"Listing Edited"`) using the **pre-edit** listing title. |
| **TC-LST-04** | `adminEditBlockedByAcceptedDeal` | Confirms an admin cannot edit a listing while an `ACCEPTED` deal exists on it (`IllegalArgumentException`), leaving the listing untouched. |
| **TC-LST-05** | `adminRemoveSoftDeletesAndNotifiesAffectedUsers` | Verifies removal rejects blank reasons, atomically soft-deletes the listing (`listingStatus = 'REMOVED'`, excluded from all finders, duplicate removal returns `false`), cancels the open `PENDING` deal while leaving `DECLINED` untouched, and notifies the seller plus the active-offer buyer only. |
| **TC-LST-06** | `inactiveAdminAndNonAdminBlockedFromListingActions` | Confirms deactivated admins are blocked from creating, editing, and removing listings, and non-admin accounts are blocked from removal. |
| **TC-LST-12** | `adminEditRejectsNonEditableStatuses` | Sets listings to `RESERVED`, `SOLD`, `LENT`, `GIVEN`, and `REMOVED`, then confirms every admin edit throws `IllegalArgumentException` and leaves status/title unchanged. |

---

### Task 3.8 — Seller Ratings Overview (`Task38_SellerRatingsOverviewTests` — 2 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-RAT-01** | `searchReviewsFiltersBySellerScoreAndDate` | Verifies `reviewRepository.searchReviews` filters by seller username keyword, exact rating score (`1..5`), and inclusive created-date bounds in newest-first order, and rejects invalid scores (`0`, `6`) or inverted date ranges. |
| **TC-RAT-02** | `hideReviewExcludesFromSellerViewAndStats` | Verifies hiding an inappropriate review persists `hidden = TRUE`, keeps it visible in admin search results marked `hidden`, hides it from the seller's review list (`findBySeller`), recalculates `SellerStatsRepository` average rating without it (`3.0 -> 5.0`), and handles blank/non-existent review IDs. |

---

## Additional Required / Edge Coverage (`Task3CoverageGapTests` — 15 tests)

| Test Case ID | Method Name | What the Test Verifies |
| :--- | :--- | :--- |
| **TC-ADM-07** | `seededHashAndLegacyMigration` | Seeded admin password is Argon2id; legacy plaintext login auto-upgrades; `migrateLegacyPasswords()` upgrades remaining legacy rows, never overwrites hashed admin passwords, and is idempotent; >1024-char login is `INVALID_INPUT`. |
| **TC-REV-06** | `sixPhotoPendingQueuePreservesOrder` | A pending listing with six photos returns all six in `photo_position` order. |
| **TC-REV-07** | `staleRejectKeepsEditedListing` | Rejecting with a stale `row_version` returns `false`, keeping the seller's edited title and `PENDING_REVIEW` status. |
| **TC-REV-08** | `buyerVisibilityTracksReviewStatus` | Pending/rejected listings are absent from buyer browse; approving to `LIVE` makes them visible. |
| **TC-USR-06** | `reverseDeletionOrderPreservesHistory` | Seller-first deletion: listings become REMOVED/SET NULL; PENDING/COUNTERED/ACCEPTED deals become CANCELLED with `deleted=1`; DECLINED/RETURNED/COMPLETED keep status; chat `deleted=1`, messages intact, report preserves buyer. Buyer-second deletion: deals/chat advance to `deleted=2` and hidden; buyer/seller logins return `USER_NOT_FOUND`. |
| **TC-USR-07** | `deleteUserAuthorizationRejectsNullAndNonAdminCallers` | Null admin and buyer callers are rejected by `AuthService.deleteUserAsAdmin` and targets survive. |
| **TC-PRF-04** | `profileUpdateIsAtomic` | Weak/oversized password update throws without changing full name or password hash. |
| **TC-DLS-02** | `dealSearchDateAndStatusFilters` | Inclusive `from`/`to`, `from==to`, one-sided date bounds, exact status filter, and exclusion of `deleted=2` deals. |
| **TC-NTF-05** | `announcementAndNotificationAuthorization` | `notifyAllAdmins("Announcement",...)` reaches admins; `NotificationService` rejects Buyer/Seller/deactivated-admin callers for read/mark operations. |
| **TC-LST-07** | `adminCreationTypesAndPhotoBoundaries` | Admin creates SELL (2-decimal price), GIVEAWAY (cleared price/duration), LEND with `LendingUnit.SECONDS`, exact six photos; empty photo throws. |
| **TC-LST-08** | `adminEditCancelsOnlyOpenProposals` | Admin edit cancels a COUNTERED deal and notifies its buyer, while DECLINED/COMPLETED deals keep status and their buyers get no `"Listing Changed"` notification. |
| **TC-LST-09** | `adminTypeChangeClearsObsoleteFields` | SELL -> LEND clears price and stores duration/unit; LEND -> GIVEAWAY clears price/duration/unit. |
| **TC-LST-10** | `adminRemovalCancelsOpenDealsAndPreservesHistory` | Verifies atomic removal cancels `COUNTERED` and `ACCEPTED` deals, preserves a `COMPLETED` deal plus chat messages/report history, removes buyer visibility, and does not notify the completed-deal buyer or another listing's buyer. |
| **TC-LST-11** | `adminListingAuthorizationMatrix` | Buyer/Seller/null callers are rejected by create/update/remove; a deactivated admin is rejected by create, update, and remove. |
| **TC-RAT-03** | `reviewUnhideFlaggedAndQueryCoverage` | Hide -> Unhide round-trip (`3.0 -> 5.0 -> 3.0`), flagged review persisted, `findAll()`, `findByRating(1..5)` incl. invalid `0/6`, and one-sided date searches. |

### Known production gaps

- `ReviewRepository.hide(String, Boolean)` negates its argument: `hide(id, false)` hides and `hide(id, true)` unhides. Tests reflect the current API; a cleaner `hide(id)`/`setHidden` API should update both test suites and `AdminDashboardController`.
- The local MySQL `listings.lending_unit` had to be migrated to include `SECONDS`; `schema.sql` already defines it.
