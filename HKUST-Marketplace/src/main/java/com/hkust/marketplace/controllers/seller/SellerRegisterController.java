package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * FXML controller for seller self-registration.
 *
 * <p>Collects seller profile, optional bio, and preferred meet-up data, validates field
 * limits, hashes the password through {@link AuthService}, and stores the account in the
 * MySQL {@code users} table.
 */
public class SellerRegisterController {

    private final UserRepository userRepository = new UserRepository();
    private final AuthService authService = new AuthService(userRepository);

    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> meetupCombo;
    @FXML private TextArea bioArea;
    @FXML private Label errorLabel;
    @FXML private Label successLabel;

    @FXML
    public void initialize() {
        meetupCombo.setItems(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
        meetupCombo.getItems().add(0, "None");
    }

    @FXML
    private void handleRegister() {
        hideMessages();
        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();


        if (username.isEmpty()) { showError("Username is required."); return; }
        if (username.codePointCount(0, username.length()) > 50) {
            showError("Username must be at most 50 characters."); return;
        }
        if (fullName.isEmpty()) { showError("Full Name cannot be empty."); return; }
        if (fullName.codePointCount(0, fullName.length()) > 100) {
            showError("Full Name must be at most 100 characters."); return;
        }
        String meetup = meetupCombo.getValue();
        if ("None".equals(meetup)) meetup = null;
        if (meetup != null && meetup.codePointCount(0, meetup.length()) > 100) {
            showError("Preferred Meet-up Location must be at most 100 characters."); return;
        }
        String bio = bioArea.getText() == null ? "" : bioArea.getText().trim();
        // MySQL TEXT is limited to 65,535 bytes rather than characters.
        if (bio.getBytes(StandardCharsets.UTF_8).length > 65_535) {
            showError("Seller Bio must be at most 65,535 UTF-8 bytes."); return;
        }
        String pwdCheck = AuthService.checkPasswordStrength(password);
        if (!"OK".equals(pwdCheck)) { showError(pwdCheck); return; }
        if (!password.equals(confirm)) { showError("Passwords do not match."); return; }

        User user = new User(username.toLowerCase(Locale.ROOT), fullName, null, UserRole.SELLER);
        if (meetup != null) user.setPreferredMeetup(meetup);
        if (!bio.isBlank()) user.setBio(bio);
        try {
            if (userRepository.existsByUsername(user.getUsername())) {
                showError("Username already taken.");
                return;
            }
            authService.register(user, password);

            showSuccess("Seller account created! You can now login.");
            usernameField.clear(); fullNameField.clear(); passwordField.clear();
            confirmPasswordField.clear(); meetupCombo.getSelectionModel().clearSelection(); bioArea.clear();
        } catch (IllegalArgumentException e) {
            // The repository also checks for an existing username.
            showError(e.getMessage());
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                // MySQL can reject a duplicate inserted after the initial check.
                showError("Username already taken.");
            } else {
                showError("Unable to create your account. Please try again later.");
            }
        }
    }

    private void showError(String msg) {
        errorLabel.setText(msg); errorLabel.setVisible(true); errorLabel.setManaged(true);
    }
    private void showSuccess(String msg) {
        successLabel.setText(msg); successLabel.setVisible(true); successLabel.setManaged(true);
    }
    private void hideMessages() {
        errorLabel.setVisible(false); errorLabel.setManaged(false);
        successLabel.setVisible(false); successLabel.setManaged(false);
    }

    @FXML private void goLogin() { SceneManager.showSellerLogin(); }
    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
