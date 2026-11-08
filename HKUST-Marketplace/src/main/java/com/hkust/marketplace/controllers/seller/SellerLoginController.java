package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.sql.SQLException;
import java.util.Locale;

public class SellerLoginController {

    private final UserRepository userRepository = new UserRepository();

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim().toLowerCase(Locale.ROOT);
        String password = passwordField.getText();
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter both username and password.");
            return;
        }
        //login by finding actual user in the database
        User user;
        try {
            user = userRepository.findByUsername(username);
        } catch (SQLException e) {
            showError("Unable to sign in. Please try again.");
            return;
        }
        if (user == null) {
            showError("User not found.");
            return;
        }
        if (user.getRole() != UserRole.SELLER) {
            showError("This account is not a Seller account. Use the correct portal.");
            return;
        }
        // TODO: Replace the demo comparison with the shared AuthService password verification.
        if (!user.getPasswordHash().equals(password)) {
            showError("Incorrect password.");
            return;
        }
        if (!user.isActive()) {
            showError("Account deactivated.");
            return;
        }
        SessionManager.login(user);
        SceneManager.showSellerDashboard();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML private void goRegister() { SceneManager.showSellerRegister(); }
    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
