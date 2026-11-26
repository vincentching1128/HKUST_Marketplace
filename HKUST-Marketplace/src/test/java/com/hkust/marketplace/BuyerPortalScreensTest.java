package com.hkust.marketplace;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.services.BuyerDealService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class BuyerPortalScreensTest extends BuyerFxFixture {
    private Stage stage;

    private void openDashboard() throws Exception {
        fx(() -> {
            SessionManager.login(buyer);
            stage = new Stage();
            SceneManager.init(stage);
            Parent root = FXMLLoader.load(getClass().getResource("/fxml/buyer/BuyerDashboardView.fxml"));
            stage.setScene(new Scene(root, 1200, 800));
            stage.show();
            return null;
        });
    }

    @AfterEach void closeScreens() throws Exception {
        fx(() -> {
            SessionManager.logout();
            if (stage != null) stage.getScene().setRoot(new Pane());
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
            return null;
        });
    }

    @ParameterizedTest @EnumSource(ListingType.class)
    void submitFromChatAndReopenTheSameProposalFromMyDeals(ListingType type) throws Exception {
        Listing listing = live(type);
        openDashboard();
        click("View Details");
        waitFor("seller feedback", () -> labelled("No ratings yet"));
        assertTrue(fx(() -> labelled(listing.getDescription()) && labelled("Meet-up Location: Library")));
        click("Chat");
        click("Make Formal Proposal");
        waitFor("proposal dialog", () -> dialog() != null);
        if (type == ListingType.SELL) {
            fx(() -> { dialogField(TextField.class).setText("-1"); return null; });
            dialogClick("Submit");
            waitFor("price validation", () -> dialogContains("Enter a valid nonnegative price"));
            fx(() -> { dialogField(TextField.class).setText("22.50"); return null; });
        } else if (type == ListingType.LEND) {
            fx(() -> {
                controls(dialog(), DatePicker.class).forEach(date -> date.setValue(LocalDate.now().plusDays(1)));
                var times = controls(dialog(), TextField.class).stream()
                        .filter(field -> "HH:mm:ss".equals(field.getPromptText())).toList();
                times.getFirst().setText("10:00:00");
                times.getLast().setText("10:00:05");
                return null;
            });
        }
        dialogClick(type == ListingType.GIVEAWAY ? "OK" : "Submit");
        waitFor("submission confirmation", () -> dialogContains("Proposal submitted and shared in chat"));
        dialogClick("OK");
        waitFor("proposal appears in the same chat", () -> labelled("Formal proposal"));
        var deal = buys.getMyDeals(buyer).getFirst();
        assertEquals(listing.getId(), deal.getListingId());
        assertEquals(DealStatus.PENDING, deal.getStatus());
        click("My Deals");
        click("View Listing");
        waitFor("reopened listing", () -> labelled(listing.getDescription()));
        click("Back");
        click("Open Chat");
        waitFor("formal proposal", () -> labelled("Formal proposal"));
        fx(() -> { controls(root(), TextArea.class).getFirst().setText("Library pickup"); return null; });
        click("Send");
        waitFor("saved chat message", () -> labelled("Library pickup"));
        var thread = chat.openForDeal(seller, deal.getId());
        assertEquals(1, chat.getConversation(seller, thread.id(), "Library pickup").messages().size());
        fx(() -> {
            controls(root(), TextField.class).stream().filter(f -> "Search this conversation".equals(f.getPromptText()))
                    .findFirst().orElseThrow().setText("unmatched");
            return null;
        });
        click("Search");
        waitFor("filtered chat", () -> labelled("No matching messages."));
        click("Clear");
        waitFor("restored chat", () -> labelled("Library pickup"));
        click("Back to Chats");
        click("Open Chat");
        waitFor("retained chat", () -> labelled("Library pickup"));
        click("Browse Marketplace");
    }

    @Test void completePurchasePromptsForReviewAndPublishesRatingOnOtherListings() throws Exception {
        Listing listing = live(ListingType.SELL);
        var deal = buys.submit(buyer, listing.getId(), ListingType.SELL, 20.0, null, null);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        live(ListingType.GIVEAWAY);
        openDashboard();
        click("My Deals");
        click("Mark Complete");
        waitFor("rate seller dialog", () -> dialog() != null && hasDialogButton("Submit Review"));
        dialogClick("Submit Review");
        waitFor("required review feedback", () -> dialogContains("Choose a rating and write a review"));
        fx(() -> {
            dialogField(ComboBox.class).getSelectionModel().select(3);
            dialogField(TextArea.class).setText("Carefully packed");
            return null;
        });
        dialogClick("Submit Review");
        waitFor("saved review in buyer history", () -> labelled("Carefully packed"));
        assertEquals(4, buys.getHistory(buyer).getFirst().rating());
        assertEquals(ListingStatus.SOLD, listings.getListing(seller, listing.getId()).getStatus());
        click("Browse Marketplace");
        click("View Details");
        waitFor("other listing shows public review", () -> labelled("Carefully packed") && labelContains("4.00 / 5"));
        click("Seller Profile");
        waitFor("public profile shows review", () -> dialogContains("Carefully packed") && dialogContains("4.00 / 5"));
    }

    @Test void historySearchCombinesSellerCategoryAndInclusiveDates() throws Exception {
        var item = live(ListingType.GIVEAWAY);
        var deal = buys.submit(buyer, item.getId(), ListingType.GIVEAWAY, null, null, null);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        openDashboard();
        click("Deal History");
        waitFor("empty history", () -> !controls(root(), TableView.class).isEmpty() && historyTable().getItems().isEmpty());
        buys.complete(buyer, deal.getId(), buys.getMyDeal(buyer, deal.getId()).getRowVersion());
        waitFor("automatic history refresh", () -> historyTable().getItems().size() == 1);
        var closed = buys.getHistory(buyer).getFirst().closedAt().toLocalDate();
        fx(() -> {
            var table = historyTable();
            assertEquals(List.of("Item Title", "Seller", "Deal Date", "Deal Type", "Agreed terms", "Outcome"),
                    table.getColumns().stream().map(TableColumn::getText).toList());
            assertEquals("Received", table.getColumns().get(3).getCellData(0));
            assertEquals(item.getTitle(), table.getColumns().getFirst().getCellData(0));
            var search = controls(root(), TextField.class).stream()
                    .filter(f -> "Search item title or seller...".equals(f.getPromptText())).findFirst().orElseThrow();
            search.setText(seller.getUsername().toUpperCase(Locale.ROOT));
            controls(root(), ComboBox.class).getFirst().getSelectionModel().selectFirst();
            var dates = controls(root(), DatePicker.class);
            dates.getFirst().setValue(closed);
            dates.getLast().setValue(closed);
            assertEquals(1, table.getItems().size());
            search.setText("no such seller");
            assertTrue(table.getItems().isEmpty());
            search.setText(item.getTitle());
            dates.getLast().setValue(closed.minusDays(1));
            assertTrue(table.getItems().isEmpty());
            assertTrue(((Label)table.getPlaceholder()).getText().contains("end date"));
            return null;
        });
        click("Clear filters");
        waitFor("history reset", () -> historyTable().getItems().size() == 1);
    }

    @Test void notificationBoardShowsRecipientTimestampCategoryAndReadHistory() throws Exception {
        notificationRows.create(new Notification(buyer.getUsername(), "Pickup changes", "Library closes early", "Announcement"));
        notificationRows.create(new Notification(otherBuyer.getUsername(), "Private notice", "Other pickup", "Offer"));
        openDashboard();
        click("Notifications");
        waitFor("announcement", () -> labelled("Pickup changes") && labelled("Announcement") && labelled("Library closes early"));
        assertFalse(fx(() -> labelled("Private notice")));
        var timestamp = inbox(buyer).getFirst().getTimestamp();
        assertTrue(fx(() -> labelled(timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")))));
        click("Mark as read");
        waitFor("unread list cleared", () -> !labelled("Pickup changes"));
        fx(() -> { controls(root(), CheckBox.class).getFirst().fire(); return null; });
        waitFor("read history", () -> labelled("Pickup changes") && button("Read").isDisabled());
        assertFalse(inbox(otherBuyer).getFirst().isRead());
    }

    @Test void buyerCanSelfReturnAnActiveLoanAndSellerSeesItAvailableAgain() throws Exception {
        var listing = live(ListingType.LEND);
        var now = LocalDateTime.now().withNano(0);
        var start = now.minusMinutes(5);
        var due = now.plusHours(1);
        var zone = ZoneId.systemDefault();
        var earlier = Clock.fixed(start.minusMinutes(1).atZone(zone).toInstant(), zone);
        var service = new BuyerDealService(
                new DealRepository(), users, notificationRows,
                DatabaseManager::getConnection, earlier);
        var deal = service.submit(buyer, listing.getId(), ListingType.LEND, null, start, due);
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        openDashboard();
        click("My Deals");
        waitFor("loan due time", () -> labelContains("Due: " + due.format(
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH))));
        click("Return Item");
        waitFor("review prompt after return", () -> dialog() != null && hasDialogButton("Submit Review"));
        dialogClick("Cancel");
        assertEquals(DealStatus.RETURNED, buys.getHistory(buyer).getFirst().outcome());
        fx(() -> {
            SessionManager.login(seller);
            Parent sellerDashboard = FXMLLoader.load(getClass().getResource("/fxml/seller/SellerDashboardView.fxml"));
            stage.getScene().setRoot(sellerDashboard);
            return null;
        });
        click("My Listings");
        waitFor("returned listing in seller dashboard", () -> labelled(listing.getTitle()) && labelled("LIVE"));
    }

    @Test void buyerSeesCounterTermsAndCanDeclineFromMyDeals() throws Exception {
        var item = live(ListingType.SELL);
        var deal = buys.submit(buyer, item.getId(), ListingType.SELL, 12.0, null, null);
        offers.counterOffer(seller, deal.getId(), deal.getRowVersion(), 15.0, null, null);
        openDashboard();
        click("My Deals");
        waitFor("revised terms", () -> labelContains("Seller counteroffer: HK$ 15.00"));
        click("Decline Counter");
        waitFor("declined deal", () -> labelContains("Declined") && button("Accept Counter") == null);
        assertEquals(DealStatus.DECLINED, buys.getMyDeal(buyer, deal.getId()).getStatus());
    }

    @Test void listingTakenAfterOpeningDetailsCannotBeClaimed() throws Exception {
        var item = live(ListingType.GIVEAWAY);
        openDashboard();
        click("View Details");
        waitFor("live claim button", () -> button("Claim Item") != null && !button("Claim Item").isDisabled());
        var rival = buys.submit(otherBuyer, item.getId(), ListingType.GIVEAWAY, null, null, null);
        offers.acceptOffer(seller, rival.getId(), rival.getRowVersion());
        click("Claim Item");
        waitFor("claim confirmation", () -> dialogContains("seller will review"));
        dialogClick("OK");
        waitFor("availability error", () -> dialogContains("not available"));
        dialogClick("OK");
        assertTrue(buys.getMyDeals(buyer).isEmpty());
        click("Refresh Details");
        waitFor("unavailable action", () -> button("Claim Item") != null && button("Claim Item").isDisabled());
        assertTrue(fx(() -> !button("Chat").isDisabled()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accept", "Decline", "Counter sale", "Counter loan"})
    void sellersDecisionReachesBuyerBoardAndMyDeals(String decision) throws Exception {
        boolean loan = decision.equals("Counter loan");
        ListingType type = loan ? ListingType.LEND : decision.equals("Accept") ? ListingType.GIVEAWAY : ListingType.SELL;
        var listing = live(type);
        var start = LocalDateTime.now().plusDays(1).withNano(0);
        var deal = buys.submit(buyer, listing.getId(), type, type == ListingType.SELL ? 12.0 : null,
                loan ? start : null, loan ? start.plusMinutes(10) : null);
        openDashboard();
        switchDashboard(seller, "seller/SellerDashboardView");
        click("Incoming Offers");
        String expectedTitle;
        if (decision.startsWith("Counter")) {
            click("Counter Offer");
            waitFor("counter form", () -> dialog() != null && hasDialogButton("Send Counter"));
            if (!loan) fx(() -> { dialogField(TextField.class).setText("13"); return null; });
            dialogClick("Send Counter");
            waitFor("seller waiting for buyer", () -> labelled("Waiting for the buyer to accept or decline."));
            expectedTitle = "Offer Countered";
        } else {
            click(decision);
            waitFor("seller decision confirmation", () -> dialog() != null);
            dialogClick("OK");
            waitFor("seller decision saved", () -> labelled(decision.equals("Accept") ? "Proposal accepted." : "Proposal declined."));
            expectedTitle = decision.equals("Accept") ? "Offer Accepted" : "Offer Declined";
        }
        click("My Listings");
        switchDashboard(buyer, "buyer/BuyerDashboardView");
        click("Notifications");
        waitFor("buyer offer notification", () -> labelled(expectedTitle) && labelled("Offer"));
        assertEquals(0, inbox(otherBuyer).size());
        click("My Deals");
        if (decision.startsWith("Counter")) {
            click("Open Chat");
            waitFor("formal seller counter", () -> labelled("Formal counteroffer"));
            click("My Deals");
            click("Accept Counter");
            waitFor("buyer accepted terms", () -> labelled("Accepted"));
            assertEquals(ListingStatus.RESERVED, listings.getListing(seller, listing.getId()).getStatus());
            if (loan) assertTrue(fx(() -> button("Return Item").isDisabled()));
        } else {
            waitFor("buyer-visible deal status", () -> labelled(decision.equals("Accept") ? "Accepted" : "Declined"));
        }
        assertEquals(decision.equals("Decline") ? DealStatus.DECLINED : DealStatus.ACCEPTED,
                buys.getMyDeal(buyer, deal.getId()).getStatus());
    }

    @Test void sellerReplyIsVisibleInBuyerNotificationHistoryAndListingFeedback() throws Exception {
        var listing = live(ListingType.SELL);
        var request = buys.submit(buyer, listing.getId(), ListingType.SELL, 20.0, null, null);
        offers.acceptOffer(seller, request.getId(), request.getRowVersion());
        buys.complete(buyer, request.getId(), buys.getMyDeal(buyer, request.getId()).getRowVersion());
        reviews.submitReview(buyer, request.getId(), 5, "Pickup went well");
        live(ListingType.LEND);
        openDashboard();
        switchDashboard(seller, "seller/SellerDashboardView");
        click("Reviews & Feedback");
        click("Reply");
        waitFor("seller reply form", () -> dialog() != null && hasDialogButton("Send Reply"));
        fx(() -> { dialogField(TextArea.class).setText("Thanks for collecting"); return null; });
        dialogClick("Send Reply");
        waitFor("reply persisted", () -> labelContains("Thanks for collecting") && button("Reply").isDisabled());
        click("My Listings");
        switchDashboard(buyer, "buyer/BuyerDashboardView");
        click("Notifications");
        waitFor("buyer reply notification", () -> labelled("Seller Replied to Your Review") && labelContains("Thanks for collecting"));
        click("Deal History");
        waitFor("reply in rating record", () -> labelContains("Seller reply: Thanks for collecting"));
        click("Browse Marketplace");
        click("View Details");
        waitFor("reply on listing", () -> labelled("Seller reply: Thanks for collecting"));
    }

    @Test void thumbnailAndGalleryShowTheFirstAndLastStoredPhotosInOrder() throws Exception {
        Listing listing = draft(ListingType.SELL);
        var photos = new ArrayList<byte[]>();
        for (int red : List.of(15, 45, 75, 105, 135, 165)) {
            var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, red << 16);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            photos.add(png.toByteArray());
        }
        listing.setPhotos(photos);
        listings.createListing(seller, listing);
        assertTrue(listingRows.updateReview(listing.getId(), ListingStatus.LIVE, null, listing.getRowVersion()));
        openDashboard();
        waitFor("first photo used as thumbnail", () -> controls(root(), ImageView.class).stream()
                .anyMatch(view -> redChannel(view) == 15));
        click("View Details");
        waitFor("gallery controls", () -> labelled("Photo 1 of 6"));
        fx(() -> {
            var toggles = controls(root(), ToggleButton.class);
            assertEquals(6, toggles.size());
            toggles.getLast().fire();
            assertTrue(labelled("Photo 6 of 6"));
            var selected = controls(root(), ImageView.class).stream()
                    .filter(view -> view.getFitHeight() == 420).findFirst().orElseThrow();
            assertEquals(165, redChannel(selected));
            return null;
        });
    }

    private static int redChannel(ImageView view) {
        var image = view.getImage();
        if (image == null || image.getPixelReader() == null) return -1;
        return (image.getPixelReader().getArgb(0, 0) >>> 16) & 255;
    }

    @Test void runningAppReturnsExpiredLoanWhileBuyerRemainsOnNotificationBoard() throws Exception {
        var item = live(ListingType.LEND);
        var now = LocalDateTime.now().withNano(0);
        var zone = ZoneId.systemDefault();
        var beforeStart = Clock.fixed(now.minusMinutes(10).atZone(zone).toInstant(), zone);
        var service = new BuyerDealService(new DealRepository(),
                users, notificationRows, DatabaseManager::getConnection, beforeStart);
        var deal = service.submit(buyer, item.getId(), ListingType.LEND, null, now.minusMinutes(2), now.plusSeconds(8));
        offers.acceptOffer(seller, deal.getId(), deal.getRowVersion());
        var app = new MarketplaceApp();
        try {
            fx(() -> { stage = new Stage(); app.start(stage); return null; });
            click("Enter as Buyer");
            waitFor("buyer login form", () -> root().lookup("#usernameField") != null);
            fx(() -> {
                ((TextField) root().lookup("#usernameField")).setText(buyer.getUsername());
                ((PasswordField) root().lookup("#passwordField")).setText("password1");
                return null;
            });
            click("Login");
            click("Notifications");
            waitFor("acceptance notice", () -> labelled("Offer Accepted"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22);
            while (buys.getHistory(buyer).isEmpty() && System.nanoTime() < deadline) Thread.sleep(100);
            assertEquals(DealStatus.RETURNED, buys.getHistory(buyer).getFirst().outcome());
            click("Refresh");
            waitFor("scheduler notification", () -> labelled("Borrowing Returned"));
            assertEquals(ListingStatus.LIVE, listings.getListing(seller, item.getId()).getStatus());
            click("Logout");
            waitFor("logout to welcome", () -> button("Enter as Buyer") != null);
            assertFalse(fx(SessionManager::isLoggedIn));
        } finally {
            app.stop();
        }
    }

    @Test void badgeCapsAtNinetyNineAndClearsAfterBulkRead() throws Exception {
        for (int i = 1; i <= 100; i++) {
            notificationRows.create(new Notification(buyer.getUsername(), "Announcement " + i, "Schedule update", "Announcement"));
        }
        openDashboard();
        waitFor("capped notification badge", () -> labelled("99+") &&
                "Notifications, 100 unread".equals(button("Notifications").getAccessibleText()));
        click("Notifications");
        click("Mark all as read");
        waitFor("badge cleared", () -> button("Notifications").getGraphic() == null);
        assertEquals(100, inbox(buyer).stream().filter(Notification::isRead).count());
    }

    private void switchDashboard(User actor, String view) throws Exception {
        fx(() -> {
            SessionManager.login(actor);
            Parent dashboard = FXMLLoader.load(getClass().getResource("/fxml/" + view + ".fxml"));
            stage.getScene().setRoot(dashboard);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private TableView<DealHistoryEntry> historyTable() {
        return controls(root(), TableView.class).getFirst();
    }

    private Parent root() { return stage.getScene().getRoot(); }

    private static Stream<Node> tree(Node node) {
        if (node instanceof ScrollPane scroll && scroll.getContent() != null)
            return Stream.concat(Stream.of(node), tree(scroll.getContent()));
        if (node instanceof Parent parent)
            return Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(BuyerPortalScreensTest::tree));
        return Stream.of(node);
    }

    private static <T> List<T> controls(Node root, Class<T> type) {
        return tree(root).filter(type::isInstance).map(type::cast).toList();
    }

    private Button button(String text) {
        return controls(root(), Button.class).stream().filter(b -> b.getText() != null && text.equals(b.getText().strip())).findFirst().orElse(null);
    }

    private boolean labelled(String text) {
        return controls(root(), Label.class).stream().anyMatch(l -> text.equals(l.getText()));
    }

    private boolean labelContains(String text) {
        return controls(root(), Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains(text));
    }

    private void click(String name) throws Exception {
        waitFor("enabled " + name, () -> button(name) != null && !button(name).isDisabled());
        Platform.runLater(() -> button(name).fire());
    }

    private static DialogPane dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).map(w -> w.getScene().getRoot())
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElse(null);
    }

    private static <T> T dialogField(Class<T> type) { return controls(dialog().getContent(), type).getFirst(); }

    private static boolean dialogContains(String text) {
        DialogPane pane = dialog();
        return pane != null && ((pane.getContentText() != null && pane.getContentText().contains(text))
                || controls(pane, Label.class).stream().anyMatch(l -> l.getText() != null && l.getText().contains(text)));
    }

    private static boolean hasDialogButton(String text) {
        return dialog().getButtonTypes().stream().anyMatch(t -> text.equals(t.getText()));
    }

    private static void dialogClick(String text) throws Exception {
        fx(() -> {
            DialogPane pane = dialog();
            var type = pane.getButtonTypes().stream().filter(t -> text.equals(t.getText())).findFirst().orElseThrow();
            ((Button) pane.lookupButton(type)).fire();
            return null;
        });
    }

    private static void waitFor(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (fx(condition::getAsBoolean)) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + description);
    }
}
