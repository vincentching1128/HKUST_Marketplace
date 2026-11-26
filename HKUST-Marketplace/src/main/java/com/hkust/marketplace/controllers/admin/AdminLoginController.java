package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.services.AuthService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import java.sql.SQLException;



/**
 * FXML controller for the Admin login screen.
 *
 * <p>Authenticates credentials against the MySQL-backed accounts through
 * {@link AuthService}, enforces the ADMIN role and active-account checks, stores the
 * session, and opens the admin dashboard on success.
 */
public class AdminLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    private final AuthService authService = new AuthService();

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter credentials.");
            return;
        }
        AuthService.LoginResult result;
        try{
            result = authService.login(username, password, UserRole.ADMIN);
        } catch (SQLException e) {
            showError("Unable to sign in. Please check the database connection and try again.");
            return;
        }
        if (result.status() != AuthService.LoginStatus.SUCCESS) {
            showError(switch (result.status()) {
                case USER_NOT_FOUND -> "User not found.";
                case WRONG_ROLE -> "This account is not an Admin account. Use the correct portal.";
                case ACCOUNT_DEACTIVATED -> "Account deactivated.";
                case INCORRECT_PASSWORD -> "Incorrect password.";
                case ACCOUNT_CHANGED -> "Account changed during sign-in. Please try again.";
                default -> "Please enter a valid username and password.";
            });
            return;
        }
        SessionManager.login(result.user());
        SceneManager.showAdminDashboard();
        SceneManager.showLoginSuccess();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
