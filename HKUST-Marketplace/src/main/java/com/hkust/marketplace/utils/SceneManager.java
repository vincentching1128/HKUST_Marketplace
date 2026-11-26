package com.hkust.marketplace.utils;

import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;

/**
 * Central navigation helper.
 * Loads FXML scenes and keeps a reference to the primary stage.
 */
public class SceneManager {

    private static Stage primaryStage;
    private static final String FXML_BASE = "/fxml/";

    /**
     * Initializes the navigation helper with the application's primary stage.
     *
     * <p>Must be called on the JavaFX Application Thread before any
     * {@link #switchScene(String)} or {@code show*} helper. Calling it again replaces the
     * stored stage, for example after the window is recreated.
     *
     * @param stage primary stage used by all subsequent navigation calls
     */
    public static void init(Stage stage) {
        primaryStage = stage;
    }

    /**
     * Returns the stage registered by {@link #init(Stage)}.
     *
     * @return the primary stage, or {@code null} when {@link #init(Stage)} has not been called
     */
    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * Loads an FXML view from the classpath and shows it in the primary stage.
     *
     * <p>The resource is resolved as {@code /fxml/} plus {@code fxmlName}. On the first call a
     * new {@link javafx.scene.Scene} of 1200x780 is created and {@code /css/styles.css} is
     * attached when available; later calls keep that scene and its stylesheets and only replace
     * its root node. Failures (missing resource, FXML load error) are reported in a modal alert
     * and rethrown wrapped in a {@link RuntimeException}. Must be called on the JavaFX
     * Application Thread.
     *
     * @param fxmlName FXML file path relative to the {@code /fxml/} resource directory, for
     *        example {@code buyer/BuyerLoginView.fxml}
     * @throws RuntimeException if the helper was not initialized, the resource is missing, or the
     *         view cannot be loaded
     */
    public static void switchScene(String fxmlName) {
        try {
            if (primaryStage == null) {
                throw new IllegalStateException("SceneManager not initialized – primaryStage is null");
            }

            URL resource = SceneManager.class.getResource(FXML_BASE + fxmlName);
            if (resource == null) {
                throw new IOException("FXML resource not found on classpath: " + FXML_BASE + fxmlName);
            }

            System.out.println("[SceneManager] Loading: " + resource);
            FXMLLoader loader = new FXMLLoader(resource);
            Parent root = loader.load();

            Scene scene = primaryStage.getScene();
            if (scene == null) {
                scene = new Scene(root, 1200, 780);
                URL css = SceneManager.class.getResource("/css/styles.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                primaryStage.setScene(scene);
            } else {
                // Keep stylesheets when swapping root
                scene.setRoot(root);
            }
            System.out.println("[SceneManager] Switched to: " + fxmlName);
        } catch (Exception e) {
            e.printStackTrace();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = "Failed to load: " + fxmlName + "\n\n" + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage();
            System.err.println("[SceneManager] " + msg);

            // Show a dialog so the error is visible without reading the console
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Navigation Error");
                alert.setHeaderText("Could not open screen");
                alert.setContentText(msg);
                alert.showAndWait();
            } catch (Exception ignored) {
                // If even the alert fails, at least we printed to console
            }
            throw new RuntimeException(msg, e);
        }
    }

    /**
     * Shows the shared welcome screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showWelcome() {
        switchScene("WelcomeView.fxml");
    }

    /**
     * Shows the buyer login screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showBuyerLogin() {
        switchScene("buyer/BuyerLoginView.fxml");
    }

    /**
     * Shows the buyer registration screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showBuyerRegister() {
        switchScene("buyer/BuyerRegisterView.fxml");
    }

    /**
     * Shows the buyer dashboard.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showBuyerDashboard() {
        switchScene("buyer/BuyerDashboardView.fxml");
    }

    /**
     * Shows the seller login screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showSellerLogin() {
        switchScene("seller/SellerLoginView.fxml");
    }

    /**
     * Shows the seller registration screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showSellerRegister() {
        switchScene("seller/SellerRegisterView.fxml");
    }

    /**
     * Shows the seller dashboard.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showSellerDashboard() {
        switchScene("seller/SellerDashboardView.fxml");
    }

    /**
     * Shows the administrator login screen.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showAdminLogin() {
        switchScene("admin/AdminLoginView.fxml");
    }

    /**
     * Shows the administrator dashboard.
     *
     * @throws RuntimeException if the view cannot be loaded, as described in
     *         {@link #switchScene(String)}
     */
    public static void showAdminDashboard() {
        switchScene("admin/AdminDashboardView.fxml");
    }

    public static void showLoginSuccess() {
        Pane dashboard = (Pane) primaryStage.getScene().getRoot();
        Label message = new Label("Log in successful");
        message.getStyleClass().add("login-success");
        message.setManaged(false);
        message.setMouseTransparent(true);
        message.layoutXProperty().bind(dashboard.widthProperty().subtract(message.widthProperty()).subtract(20));
        message.layoutYProperty().bind(dashboard.heightProperty().subtract(message.heightProperty()).subtract(20));
        dashboard.getChildren().add(message);
        message.applyCss();
        message.autosize();

        PauseTransition dismiss = new PauseTransition(Duration.seconds(3));
        dismiss.setOnFinished(event -> dashboard.getChildren().remove(message));
        dismiss.play();
    }
}
