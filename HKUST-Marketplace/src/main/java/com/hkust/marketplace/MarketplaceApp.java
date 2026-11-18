package com.hkust.marketplace;

import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.services.BuyerDealService;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaFX entry point for the buyer, seller, and admin portals.
 * Due borrowings are returned while the application is running.
 */
public class MarketplaceApp extends Application {
    private ScheduledExecutorService returns;

    @Override
    public void init() {
        // Set the application locale before loading any FXML or creating JavaFX controls.
        Locale.setDefault(Locale.ENGLISH);
    }

    @Override
    public void start(Stage primaryStage) {
        SceneManager.init(primaryStage);
        SceneManager.showWelcome();
        primaryStage.setTitle("HKUST Marketplace");
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(700);
        primaryStage.show();
        returns = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "borrow-auto-return");
            thread.setDaemon(true);
            return thread;
        });
        BuyerDealService deals = new BuyerDealService();
        AtomicBoolean returnCheckFailed = new AtomicBoolean();
        returns.scheduleWithFixedDelay(() -> {
            try {
                deals.returnDueBorrowings();
                returnCheckFailed.set(false);
            } catch (Exception failure) {
                if (!returnCheckFailed.getAndSet(true))
                    System.err.println("Borrow auto-return check failed: " + failure.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        AtomicBoolean reminderCheckFailed = new AtomicBoolean();
        returns.scheduleWithFixedDelay(() -> {
            try {
                deals.remindDueBorrowings();
                reminderCheckFailed.set(false);
            } catch (Exception failure) {
                if (!reminderCheckFailed.getAndSet(true))
                    System.err.println("Borrow reminder check failed: " + failure.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override public void stop() {
        if (returns != null) returns.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
