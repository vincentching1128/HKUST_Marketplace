package com.hkust.marketplace;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit support base for JavaFX buyer-portal tests that require a running MySQL fixture.
 *
 * <p>Starts the shared JavaFX toolkit once and exposes {@link #fx(Callable)} for safely
 * executing UI actions on the JavaFX Application Thread; extends {@link MySqlFixture}.
 */
public abstract class BuyerFxFixture extends MySqlFixture {
    @BeforeAll static void startJavaFx() throws Exception {
        var ready = new CountDownLatch(1);
        Runnable initialize = () -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        };
        try {
            Platform.startup(initialize);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(initialize);
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX startup timed out");
    }

    protected static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
