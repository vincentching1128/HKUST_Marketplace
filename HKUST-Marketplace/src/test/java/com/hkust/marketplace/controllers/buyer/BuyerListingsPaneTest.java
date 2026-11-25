package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.BuyerFxFixture;
import com.hkust.marketplace.models.*;
import com.hkust.marketplace.services.BuyerListingService;
import com.hkust.marketplace.utils.SessionManager;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BuyerListingsPaneTest extends BuyerFxFixture {
    static final User BUYER = new User("buyer", "Buyer", "unused", UserRole.BUYER);
    FakeService service;
    BuyerListingsPane pane;

    @BeforeEach void setup() throws Exception {
        service = new FakeService();
        fx(() -> {
            pane = new BuyerListingsPane(service, () -> BUYER, ignored -> {});
            Scene scene = new Scene(pane, 950, 700);
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            pane.resize(950, 700); pane.showBrowse(); pane.applyCss(); pane.layout(); return null;
        });
        await(() -> text("2 listings"));
        fx(() -> { pane.applyCss(); pane.layout(); return null; });
    }
    @AfterEach void closeDialogs() throws Exception {
        fx(() -> { for (Window window : List.copyOf(Window.getWindows())) window.hide(); return null; });
    }

    @Test void browseFiltersResetAndValidationWork() throws Exception {
        fx(() -> {
            assertTrue(text("Availability: Available"));
            assertTrue(text("For Sale · Asking price: HK$ 12.50"));
            assertTrue(text("No photo available"));
            field("Search listings by title or description").setText("CALCULUS");
            button("Search").fire(); assertTrue(text("1 listing"));
            button("Reset").fire(); assertTrue(text("2 listings"));
            combo("Listing type").setValue("Lend"); assertTrue(text("1 listing"));
            combo("Condition").setValue("New"); assertTrue(text("No listings match these filters."));
            button("Reset").fire();
            field("Minimum sale price").setText("13");
            field("Maximum sale price").setText("12");
            button("Search").fire(); assertTrue(text("Minimum price must not exceed maximum price."));
            field("Minimum sale price").setText("abc");
            button("Search").fire(); assertTrue(text("Enter prices from 0 to 99,999,999.99 with at most two decimal places."));
            button("Reset").fire(); assertTrue(text("2 listings"));
            combo("Category").setValue("Books"); assertTrue(text("1 listing"));
            return null;
        });
    }

    @Test void cardsShowListingFieldsAndPriceSortChangesVisibleOrder() throws Exception {
        fx(() -> {
            for (String expected : List.of("book", "Books · Used", "Seller: Seller Name", "Meet-up: Library",
                    "Posted: 02 Jan 2026, 12:00", "Availability: Available", "For Sale · Asking price: HK$ 12.50")) {
                assertTrue(text(expected), expected);
            }
            combo("Sort listings").setValue("Price: low to high");
            assertEquals(List.of("calculator", "book"), visibleTitles());
            combo("Sort listings").setValue("Price: high to low");
            assertEquals(List.of("book", "calculator"), visibleTitles());
            combo("Listing type").setValue("Giveaway");
            assertTrue(text("No listings match these filters."));
            combo("Listing type").setValue("Sell");
            assertEquals(List.of("book"), visibleTitles());
            return null;
        });
    }

    private List<String> visibleTitles() {
        return nodes(pane).stream().filter(Label.class::isInstance).map(Label.class::cast).map(Label::getText)
                .filter(title -> "book".equals(title) || "calculator".equals(title)).toList();
    }

    @Test void detailSupportsSixCorruptPhotosAndBackPreservesFilters() throws Exception {
        fx(() -> {
            combo("Category").setValue("Books");
            button("View Details").fire(); return null;
        });
        await(() -> text("Seller Username: seller"));
        fx(() -> {
            assertEquals(6, nodes(pane).stream().filter(n -> n instanceof ToggleButton).count());
            assertTrue(text("Photo 1 of 6"));
            assertTrue(text("This photo could not be displayed."));
            ToggleButton sixth = nodes(pane).stream().filter(n -> "Show photo 6".equals(n.getAccessibleText()))
                    .map(n -> (ToggleButton)n).findFirst().orElseThrow();
            sixth.fire(); assertTrue(text("Photo 6 of 6"));
            assertFalse(button("Make Offer").isDisabled());
            assertFalse(button("Chat").isDisabled());
            assertFalse(button("Report Listing").isDisabled());
            assertTrue(text("Calculus course materials"));
            button("Back").fire(); return null;
        });
        await(() -> text("1 listing"));
        fx(() -> { assertEquals("Books", combo("Category").getValue()); return null; });
    }

    @Test void refreshFailureAndRecoveryHaveDistinctEmptyState() throws Exception {
        service.failure = true;
        fx(() -> { button("Refresh").fire(); return null; });
        await(() -> text("Unable to load listings. Please try again."));
        service.failure = false;
        service.rows = List.of();
        fx(() -> { button("Retry").fire(); return null; });
        await(() -> text("No live listings at the moment."));
    }

    @Test void invalidFiltersAfterRefreshDoNotLeaveLoadingSpinner() throws Exception {
        fx(() -> {
            field("Minimum sale price").setText("invalid");
            button("Refresh").fire(); return null;
        });
        await(() -> text("Enter prices from 0 to 99,999,999.99 with at most two decimal places."));
        fx(() -> { assertFalse(text("Loading listings...")); return null; });
    }

    @Test void detachedPaneDiscardsLateDatabaseResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        service.entered = entered; service.release = release;
        fx(() -> { pane.showBrowse(); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fx(() -> { pane.getScene().setRoot(new VBox()); return null; });
        release.countDown();
        Thread.sleep(150);
        fx(() -> { assertTrue(text("Loading listings...")); return null; });
    }

    @Test void completeDashboardFxmlLoadsAtBothWidths() throws Exception {
        for (int width : new int[]{1200, 900}) {
            Region root = fx(() -> {
                SessionManager.login(BUYER);
                BuyerDashboardController controller = new BuyerDashboardController();
                var field = BuyerDashboardController.class.getDeclaredField("listingsPane");
                field.setAccessible(true); field.set(controller, pane);
                pane.getScene().setRoot(new VBox());
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/buyer/BuyerDashboardView.fxml"));
                loader.setControllerFactory(type -> controller);
                Region dashboard = loader.load();
                Scene scene = new Scene(dashboard, width, 780);
                scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
                dashboard.resize(width, 780); dashboard.applyCss(); dashboard.layout();
                return dashboard;
            });
            await(() -> text("2 listings"));
            fx(() -> {
                root.resize(width, 780); root.applyCss(); root.layout();
                assertTrue(pane.getWidth() > 500); return null;
            });
        }
        fx(() -> { SessionManager.logout(); return null; });
    }

    @Test void reportRequiresReasonAndSavesOnce() throws Exception {
        fx(() -> { pane.showListingDetails("book"); return null; });
        await(() -> text("Seller Username: seller"));
        // Queue the click, then fill the modal through JavaFX's nested event loop.
        Platform.runLater(() -> button("Report Listing").fire());
        await(() -> windows().stream().anyMatch(n -> n instanceof TextArea));
        fx(() -> {
            List<Node> controls = windows();
            Button submit = controls.stream().filter(n -> n instanceof Button b && b.getText().equals("Submit Report"))
                    .map(n -> (Button)n).findFirst().orElseThrow();
            TextArea reason = controls.stream().filter(n -> n instanceof TextArea).map(n -> (TextArea)n).findFirst().orElseThrow();
            assertTrue(submit.isDisabled());
            reason.setText(" "); assertTrue(submit.isDisabled());
            reason.setText("a".repeat(2001)); assertTrue(submit.isDisabled());
            reason.setText("  Misleading description  "); assertFalse(submit.isDisabled());
            submit.fire(); return null;
        });
        await(() -> service.reportCalls == 1);
        await(() -> windows().stream().anyMatch(n -> n instanceof Label l && l.getText().equals("Your listing report has been saved.")));
        assertEquals("book", service.reportId);
        assertEquals("Misleading description", service.reportReason);
    }

    private boolean text(String text) { return nodes(pane).stream().anyMatch(n -> n instanceof Label l && text.equals(l.getText())); }
    private Button button(String text) { return nodes(pane).stream().filter(n -> n instanceof Button b && text.equals(b.getText())).map(n -> (Button)n).findFirst().orElseThrow(); }
    private TextField field(String name) { return (TextField)nodes(pane).stream().filter(n -> name.equals(n.getAccessibleText())).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked") private ComboBox<String> combo(String name) { return (ComboBox<String>)nodes(pane).stream().filter(n -> name.equals(n.getAccessibleText())).findFirst().orElseThrow(); }
    private static List<Node> windows() { return List.copyOf(Window.getWindows()).stream().flatMap(w -> nodes(w.getScene().getRoot()).stream()).toList(); }
    private static List<Node> nodes(Node root) {
        List<Node> result = new ArrayList<>(); result.add(root);
        if (root instanceof ScrollPane scroll && scroll.getContent() != null) result.addAll(nodes(scroll.getContent()));
        else if (root instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) result.addAll(nodes(child));
        return result;
    }
    private static void await(BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) { if (fx(done::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for JavaFX state");
    }
    private static Listing fixture(String id, ListingType type, String category, int day) {
        Listing listing = new Listing(); listing.setId(id); listing.setTitle(id); listing.setDescription("Calculus course materials");
        listing.setType(type); listing.setCategory(category); listing.setCondition("Used"); listing.setPrice(type == ListingType.SELL ? 12.5 : null);
        listing.setSellerFullName("Seller Name"); listing.setSellerUsername("seller"); listing.setMeetupLocation("Library");
        listing.setStatus(ListingStatus.LIVE); listing.setPostedAt(LocalDateTime.of(2026, 1, day, 12, 0)); return listing;
    }
    private static class FakeService extends BuyerListingService {
        volatile List<Listing> rows = List.of(fixture("book", ListingType.SELL, "Books", 2), fixture("calculator", ListingType.LEND, "Electronics", 1));
        volatile boolean failure;
        volatile int reportCalls;
        volatile String reportId, reportReason;
        volatile CountDownLatch entered, release;
        FakeService() { rows.get(1).setDescription("A scientific calculator"); }
        @Override public List<Listing> getLiveListings(User user) throws SQLException {
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("test gate timeout"); }
                catch (InterruptedException e) { throw new SQLException(e); }
            }
            if (failure) throw new SQLException("offline"); return rows;
        }
        @Override public Listing getListing(User user, String id) {
            Listing detail = fixture(id, ListingType.SELL, "Books", 2);
            detail.setPhotos(List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}, new byte[]{5}, new byte[]{6}));
            return detail;
        }
        @Override public void reportListing(User user, String id, String reason) { reportId = id; reportReason = reason; reportCalls++; }
    }
}
