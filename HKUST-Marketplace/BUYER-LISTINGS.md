# Buyer marketplace listings

Buyer Browse and Listing Detail use MySQL through `BuyerListingService` and
`BuyerListingRepository`. Seller/admin controllers, services and queries are unchanged.
The gallery follows the seller's existing read-only gallery pattern.

## Setup

Use JDK 21 and the existing database connection configuration. After the base
`src/main/resources/db/schema.sql`, run
`src/main/resources/db/buyer_listing_reports.sql` against `hkust_marketplace`.
The additional script is safe to rerun and preserves existing reports. Browse and
Detail work without this migration; submitting a report requires it.
Both scripts must be run by an account with table-creation permission. If the
database has only account tables, apply the base schema first to create `listings`
and `listing_photos`; the buyer screens cannot load listings until those exist.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd javafx:run
```

## Implemented behaviour

- Browse shows only live listings belonging to active sellers and checks that the
  signed-in database account is an active buyer.
- Cards show the first saved photo, title, seller, category, type, sale price to two
  decimal places, condition, meet-up location, posted date and availability.
- Search matches a literal, case-insensitive substring in the title or description.
  Category, type, condition and inclusive minimum/maximum sale-price filters combine.
  Price bounds accept 0 to 99,999,999.99 with at most two decimal places. A price
  range excludes lending/giveaway items, which have no sale price.
- Newest is the default sort. Price sorts put unpriced items last in both directions;
  newest date and listing ID break ties. Search/Enter applies text fields; changing
  a dropdown also applies the current text fields. Reset clears all filters.
- Refresh and Back to Browse reload database listings while keeping filter choices.
  Database failures show an error and retry, distinct from an empty marketplace.
- View Details reloads the listing and all saved photos (up to six), showing full
  description, seller name/username, fields, applicable sale/lending terms and
  availability. Missing or corrupt photos have a fallback. Reserved, sold, lent
  and given items remain readable by ID; pending, rejected, removed or deleted
  listings, and listings from inactive sellers, are inaccessible.
- Report Listing accepts a nonblank reason up to 2,000 Unicode characters. The
  database transaction rechecks and locks the active buyer and visible listing,
  then records the report, timestamp, buyer, listing and seller/title snapshots.
  Confirmation appears only after commit. Deleting a listing does not block the
  existing seller workflow: the report retains its snapshot with a null listing ID.
- Database work runs outside the JavaFX UI thread. Late results are ignored after
  navigating away or starting a newer request.

## Deliberately separate features

Offer/borrow/claim actions and Chat have disabled buttons with an explanation;
seller ratings explicitly show that ratings are not available yet. These screens
do not fabricate submissions, reviews, averages or chat history. Their shared
backends and an admin report-review screen are separate work.

The detail entry point accepts a saved listing ID for future My Deals integration.
The current hardcoded My Deals rows are unchanged and cannot identify real database
deals, so navigation from that screen remains pending that feature.

## Verification checklist

1. Log in as a buyer. Confirm only approved live listings appear, including newly
   approved listings after Refresh; rejected/pending/removed listings do not appear.
2. Combine search, category, type, condition and price bounds. Check both inclusive
   endpoints, zero results, invalid/reversed ranges, reset and all three sorts.
3. Open a listing with six photos and select each. Check listings with no photos
   and unreadable photo bytes; fields remain readable. Check fractional sale prices.
4. Change a listing's status from another session, then open/refresh its details.
   Unavailable approved items show their status; removed/private items cannot load.
5. Report a visible listing; verify the new row in `listing_reports`. Blank/oversized
   reasons must be blocked. A removed listing or suspended buyer must not create a
   report. Simulate a database failure and confirm no success message appears.
6. Navigate away while loading and verify late results do not replace the active
   dashboard screen. Return to Browse to retry and load current data.

The repository intentionally ignores `src/test/`; local verification tests follow
that policy. Existing local tests may target older model APIs and need independent
maintenance before a complete local `mvn test` run can pass.
