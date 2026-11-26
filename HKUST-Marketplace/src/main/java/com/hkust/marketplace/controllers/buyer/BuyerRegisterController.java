package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.utils.SceneManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.SQLException;
import java.util.Locale;

/**
 * FXML controller for buyer self-registration.
 *
 * <p>Collects buyer profile and optional residence-hall data, validates field limits,
 * hashes the password through {@link AuthService}, and persists the new account in the
 * MySQL {@code users} table while reporting duplicate-username and storage failures.
 */
public class BuyerRegisterController {

    private final UserRepository userRepository = new UserRepository();
    private final AuthService authService = new AuthService(userRepository);

    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> residenceCombo;
    @FXML private Label errorLabel;
    @FXML private Label successLabel;

    @FXML
    public void initialize() {
        residenceCombo.setItems(FXCollections.observableArrayList(
                "Hall 1", "Hall 2", "Hall 3", "Hall 4", "Hall 5",
                "Hall 6", "Hall 7", "Hall 8", "Hall 9",
                "UG Hall 1", "UG Hall 2", "Off-campus"
        ));
    }

    @FXML
    private void handleRegister() {
        hideMessages();

        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();
        String residence = residenceCombo.getValue();

        // Validation
        if (username.isEmpty()) {
            showError("Username is required.");
            return;
        }
        if (username.codePointCount(0, username.length()) > 50) {
            showError("Username must be at most 50 characters.");
            return;
        }
        if (fullName.isEmpty()) {
            showError("Full Name cannot be empty.");
            return;
        }
        if (fullName.codePointCount(0, fullName.length()) > 100) {
            showError("Full Name must be at most 100 characters.");
            return;
        }
        if (residence != null && residence.codePointCount(0, residence.length()) > 100) {
            showError("Residence must be at most 100 characters.");
            return;
        }
        String pwdCheck = AuthService.checkPasswordStrength(password);
        if (!"OK".equals(pwdCheck)) {
            showError(pwdCheck);
            return;
        }
        if (!password.equals(confirm)) {
            showError("Passwords do not match.");
            return;
        }

        User user = new User(username.toLowerCase(Locale.ROOT), fullName, null, UserRole.BUYER);
        if (residence != null) {
            user.setResidenceHall(residence);
        }
        try {
            if (userRepository.existsByUsername(user.getUsername())) {
                showError("Username already taken. Please choose another.");
                return;
            }
            authService.register(user, password);

            showSuccess("Registration successful! You can now login.");
            usernameField.clear();
            fullNameField.clear();
            passwordField.clear();
            confirmPasswordField.clear();
            residenceCombo.getSelectionModel().clearSelection();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                showError("Username already taken. Please choose another.");
            } else {
                showError("Unable to create your account. Please try again later.");
            }
        }
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void showSuccess(String msg) {
        successLabel.setText(msg);
        successLabel.setVisible(true);
        successLabel.setManaged(true);
    }

    private void hideMessages() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        successLabel.setVisible(false);
        successLabel.setManaged(false);
    }

    @FXML private void goLogin() { SceneManager.showBuyerLogin(); }
    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
