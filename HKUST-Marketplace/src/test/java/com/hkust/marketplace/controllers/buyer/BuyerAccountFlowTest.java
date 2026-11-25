package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.BuyerFxFixture;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.services.PasswordHasher;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BuyerAccountFlowTest extends BuyerFxFixture {
    private final UserRepository users = new UserRepository();

    @BeforeEach void prepareStage() throws Exception {
        fx(() -> { SceneManager.init(new Stage()); SessionManager.logout(); return null; });
    }

    @AfterEach void cleanup() throws Exception {
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
            SessionManager.logout();
            return null;
        });
    }

    @Test void registrationPersistsHashAndRejectsCrossRoleDuplicatesAndInvalidInput() throws Exception {
        String username = fixture();
        fx(() -> {
            BuyerRegisterController controller = load("BuyerRegisterView.fxml");
            fillRegistration(controller, username, "Buyer One", "OriginalPassword123!");
            invoke(controller, "handleRegister");
            assertTrue(label(controller, "successLabel").isVisible());
            assertEquals("", text(controller, "usernameField").getText());
            return null;
        });
        User saved = users.findByUsername(username);
        assertNotNull(saved);
        assertEquals(UserRole.BUYER, saved.getRole());
        assertEquals("Hall 3", saved.getResidenceHall());
        assertTrue(PasswordHasher.verify("OriginalPassword123!", saved.getPasswordHash()));
        assertNull(DummyDataService.getInstance().findUser(username));

        String seller = fixture();
        new AuthService().register(new User(seller, "Seller", null, UserRole.SELLER), "OriginalPassword123!");
        fx(() -> {
            BuyerRegisterController controller = load("BuyerRegisterView.fxml");
            fillRegistration(controller, " " + seller.toUpperCase(Locale.ROOT) + " ", "Buyer", "OriginalPassword123!");
            invoke(controller, "handleRegister");
            assertTrue(label(controller, "errorLabel").getText().contains("already taken"));
            assertFalse(label(controller, "successLabel").isVisible());
            fillRegistration(controller, "x".repeat(51), "Buyer", "OriginalPassword123!");
            invoke(controller, "handleRegister");
            assertTrue(label(controller, "errorLabel").getText().contains("50 characters"));
            fillRegistration(controller, username, "Buyer", "OriginalPassword123!");
            text(controller, "confirmPasswordField").setText("DifferentPassword123!");
            invoke(controller, "handleRegister");
            assertEquals("Passwords do not match.", label(controller, "errorLabel").getText());
            return null;
        });
        assertEquals(UserRole.SELLER, users.findByUsername(seller).getRole());
    }

    @Test void registrationReportsEachPasswordRuleAndRequiresAName() throws Exception {
        String username = fixture();
        fx(() -> {
            BuyerRegisterController form = load("BuyerRegisterView.fxml");
            for (String weak : List.of("Short1", "alllower1", "ALLUPPER1", "NoDigitsHere")) {
                fillRegistration(form, username, "Buyer", weak);
                invoke(form, "handleRegister");
                assertTrue(label(form, "errorLabel").isVisible(), weak);
                assertNull(users.findByUsername(username));
            }
            fillRegistration(form, username, " ", "ValidPass1");
            invoke(form, "handleRegister");
            assertEquals("Full Name cannot be empty.", label(form, "errorLabel").getText());
            for (String duplicate : List.of("alice", "admin", "charlie")) {
                fillRegistration(form, duplicate, "New buyer", "ValidPass1");
                invoke(form, "handleRegister");
                assertTrue(label(form, "errorLabel").getText().contains("already taken"));
            }
            fillRegistration(form, username, "Buyer", "ValidPass1");
            ((ComboBox<?>) field(form, "residenceCombo")).setValue(null);
            invoke(form, "handleRegister");
            assertTrue(label(form, "successLabel").isVisible());
            assertNull(users.findByUsername(username).getResidenceHall());
            return null;
        });
    }

    @Test void loginAndProfileChangesUseDatabaseAndPersistAcrossFreshControllers() throws Exception {
        String username = fixture();
        User buyer = new User(username, "Database Name", null, UserRole.BUYER);
        buyer.setResidenceHall("Hall 3");
        buyer.setBio("Preserve bio");
        buyer.setPreferredMeetup("Library");
        new AuthService().register(buyer, "OriginalPassword123!");
        fx(() -> {
            BuyerLoginController login = load("BuyerLoginView.fxml");
            text(login, "usernameField").setText(username.toUpperCase(Locale.ROOT));
            text(login, "passwordField").setText("wrong");
            invoke(login, "handleLogin");
            assertFalse(SessionManager.isLoggedIn());
            assertTrue(label(login, "errorLabel").getText().contains("Incorrect password"));
            text(login, "passwordField").setText("OriginalPassword123!");
            invoke(login, "handleLogin");
            assertTrue(SessionManager.isBuyer());
            assertEquals(username, SessionManager.getCurrentUser().getUsername());

            User session = SessionManager.getCurrentUser();
            session.setFullName("Stale Session Name");
            BuyerDashboardController dashboard = load("BuyerDashboardView.fxml");
            invoke(dashboard, "showProfile");
            VBox form = (VBox) ((StackPane) field(dashboard, "contentPane")).getChildren().getFirst();
            TextField name = (TextField) form.getChildren().stream()
                    .filter(n -> n instanceof TextField && !(n instanceof PasswordField)).findFirst().orElseThrow();
            List<PasswordField> passwords = form.getChildren().stream().filter(PasswordField.class::isInstance)
                    .map(PasswordField.class::cast).toList();
            @SuppressWarnings("unchecked")
            ComboBox<String> residence = (ComboBox<String>) form.getChildren().stream()
                    .filter(ComboBox.class::isInstance).findFirst().orElseThrow();
            Button save = (Button) form.getChildren().stream().filter(Button.class::isInstance).findFirst().orElseThrow();
            assertEquals("Database Name", name.getText());
            assertEquals("Hall 3", residence.getValue());
            String originalHash = users.findByUsername(username).getPasswordHash();

            name.setText("Updated Buyer");
            residence.setValue("Hall 5");
            passwords.forEach(p -> p.setText("weak"));
            assertTrue(fireAndDismiss(save).contains("8 characters"));
            assertEquals("Database Name", users.findByUsername(username).getFullName());
            assertEquals("Stale Session Name", session.getFullName());
            passwords.forEach(TextField::clear);
            assertEquals("Profile updated successfully.", fireAndDismiss(save));
            assertEquals(originalHash, users.findByUsername(username).getPasswordHash());
            assertEquals("Updated Buyer", session.getFullName());
            assertEquals("Hall 5", session.getResidenceHall());

            passwords.getFirst().setText("ChangedPassword123!");
            passwords.getLast().setText("Mismatch123!");
            assertEquals("Passwords do not match.", fireAndDismiss(save));
            assertEquals(originalHash, users.findByUsername(username).getPasswordHash());
            passwords.getLast().setText("ChangedPassword123!");
            assertEquals("Profile updated successfully.", fireAndDismiss(save));
            assertTrue(passwords.stream().allMatch(p -> p.getText().isEmpty()));
            assertTrue(PasswordHasher.verify("ChangedPassword123!", session.getPasswordHash()));
            SessionManager.logout();
            BuyerLoginController freshLogin = load("BuyerLoginView.fxml");
            text(freshLogin, "usernameField").setText(username);
            text(freshLogin, "passwordField").setText("OriginalPassword123!");
            invoke(freshLogin, "handleLogin");
            assertFalse(SessionManager.isLoggedIn());
            text(freshLogin, "passwordField").setText("ChangedPassword123!");
            invoke(freshLogin, "handleLogin");
            assertTrue(SessionManager.isBuyer());
            return null;
        });
        User persisted = new UserRepository().findByUsername(username);
        assertEquals("Updated Buyer", persisted.getFullName());
        assertEquals("Hall 5", persisted.getResidenceHall());
        assertEquals("Preserve bio", persisted.getBio());
        assertEquals("Library", persisted.getPreferredMeetup());
    }

    @Test void buyerLoginRejectsSellerAndDeactivatedDatabaseAccounts() throws Exception {
        String seller = fixture();
        new AuthService().register(new User(seller, "Seller", null, UserRole.SELLER), "OriginalPassword123!");
        String buyer = fixture();
        new AuthService().register(new User(buyer, "Buyer", null, UserRole.BUYER), "OriginalPassword123!");
        users.setActive(buyer, false);
        fx(() -> {
            BuyerLoginController login = load("BuyerLoginView.fxml");
            text(login, "usernameField").setText(seller);
            text(login, "passwordField").setText("OriginalPassword123!");
            invoke(login, "handleLogin");
            assertTrue(label(login, "errorLabel").getText().contains("not a Buyer"));
            assertFalse(SessionManager.isLoggedIn());
            text(login, "usernameField").setText(buyer);
            invoke(login, "handleLogin");
            assertTrue(label(login, "errorLabel").getText().contains("deactivated"));
            assertFalse(SessionManager.isLoggedIn());
            return null;
        });
    }

    @Test void databaseFailuresDisplayErrorsWithoutChangingSession() throws Exception {
        UserRepository disconnected = new UserRepository() {
            @Override public User findByUsername(String username) throws SQLException { throw new SQLException("Offline"); }
            @Override public boolean existsByUsername(String username) throws SQLException { throw new SQLException("Offline"); }
        };
        fx(() -> {
            BuyerRegisterController register = load("BuyerRegisterView.fxml");
            setField(register, "userRepository", disconnected);
            fillRegistration(register, "offline_buyer", "Buyer", "OriginalPassword123!");
            invoke(register, "handleRegister");
            assertTrue(label(register, "errorLabel").getText().contains("Unable to create"));
            assertFalse(label(register, "successLabel").isVisible());
            assertEquals("offline_buyer", text(register, "usernameField").getText());
            BuyerLoginController login = load("BuyerLoginView.fxml");
            setField(login, "authService", new AuthService(disconnected));
            text(login, "usernameField").setText("offline_buyer");
            text(login, "passwordField").setText("OriginalPassword123!");
            invoke(login, "handleLogin");
            assertTrue(label(login, "errorLabel").getText().contains("database connection"));
            assertFalse(SessionManager.isLoggedIn());
            return null;
        });
    }

    @Test void buyerCredentialsCannotOpenSellerOrAdminPortal() throws Exception {
        fx(() -> {
            for (String role : List.of("Seller", "Admin")) {
                String view = "/fxml/" + role.toLowerCase(Locale.ROOT) + "/" + role + "LoginView.fxml";
                FXMLLoader loader = new FXMLLoader(getClass().getResource(view));
                loader.load();
                Object controller = loader.getController();
                text(controller, "usernameField").setText("alice");
                text(controller, "passwordField").setText("password1");
                invoke(controller, "handleLogin");
                assertTrue(label(controller, "errorLabel").getText().contains("correct portal"));
                assertFalse(SessionManager.isLoggedIn());
            }
            return null;
        });
    }

    @Test void registrationLengthLimitsAndEmptyFieldsGiveFeedbackWithoutCreatingAnAccount() throws Exception {
        fx(() -> {
            var form = (BuyerRegisterController) load("BuyerRegisterView.fxml");
            for (var invalid : List.of(
                    List.of("", "Buyer One", "ValidPass1", "Username is required"),
                    List.of("validbuyer", "A".repeat(101), "ValidPass1", "100 characters"),
                    List.of("validbuyer", "Buyer One", "", "cannot be empty"),
                    List.of("validbuyer", "Buyer One", "Aa1" + "x".repeat(1022), "1024 characters"))) {
                fillRegistration(form, invalid.get(0), invalid.get(1), invalid.get(2));
                invoke(form, "handleRegister");
                assertTrue(label(form, "errorLabel").getText().contains(invalid.get(3)));
                assertFalse(label(form, "successLabel").isVisible());
            }
            assertNull(users.findByUsername("validbuyer"));
            return null;
        });
    }

    @Test void blankAndUnknownCredentialsKeepBuyerOnLoginScreen() throws Exception {
        fx(() -> {
            var login = (BuyerLoginController) load("BuyerLoginView.fxml");
            for (var invalid : List.of(List.of("", "password1"), List.of("alice", ""))) {
                text(login, "usernameField").setText(invalid.getFirst());
                text(login, "passwordField").setText(invalid.getLast());
                invoke(login, "handleLogin");
                assertTrue(label(login, "errorLabel").getText().contains("both username and password"));
                assertFalse(SessionManager.isLoggedIn());
            }
            text(login, "usernameField").setText("unknown_buyer");
            text(login, "passwordField").setText("ValidPass1");
            invoke(login, "handleLogin");
            assertTrue(label(login, "errorLabel").getText().contains("User not found"));
            assertFalse(SessionManager.isLoggedIn());
            return null;
        });
    }

    private String fixture() {
        String name = "buyer_it_" + UUID.randomUUID().toString().replace("-", "");

        return name;
    }

    private static <T> T load(String fxml) throws Exception {
        FXMLLoader loader = new FXMLLoader(BuyerAccountFlowTest.class.getResource("/fxml/buyer/" + fxml));
        Parent root = loader.load();
        return loader.getController();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TextField text(Object target, String name) throws Exception { return (TextField) field(target, name); }
    private static Label label(Object target, String name) throws Exception { return (Label) field(target, name); }

    private static void fillRegistration(BuyerRegisterController controller, String username, String name, String password) throws Exception {
        text(controller, "usernameField").setText(username);
        text(controller, "fullNameField").setText(name);
        text(controller, "passwordField").setText(password);
        text(controller, "confirmPasswordField").setText(password);
        @SuppressWarnings("unchecked") ComboBox<String> residence = (ComboBox<String>) field(controller, "residenceCombo");
        residence.setValue("Hall 3");
    }

    private static void invoke(Object controller, String method) throws Exception {
        var action = controller.getClass().getDeclaredMethod(method);
        action.setAccessible(true);
        try { action.invoke(controller); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }

    private static String fireAndDismiss(Button button) {
        List<String> messages = new ArrayList<>();
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane) {
                    messages.add(pane.getContentText());
                    window.hide();
                }
            }
        });
        button.fire();
        assertEquals(1, messages.size(), "Expected one profile result dialog");
        return messages.getFirst();
    }

}
