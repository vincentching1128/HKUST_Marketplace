package com.hkust.marketplace.controllers;

import com.hkust.marketplace.utils.SceneManager;
import javafx.fxml.FXML;

/**
 * Landing-screen controller for choosing the Buyer, Seller, or Admin portal.
 *
 * <p>Each action delegates navigation to {@link SceneManager} after the corresponding
 * login view is selected; this controller does not change account state.
 */
public class WelcomeController {

    @FXML
    private void goBuyer() {
        System.out.println("[Welcome] goBuyer()");
        SceneManager.showBuyerLogin();
    }

    @FXML
    private void goSeller() {
        System.out.println("[Welcome] goSeller()");
        SceneManager.showSellerLogin();
    }

    @FXML
    private void goAdmin() {
        System.out.println("[Welcome] goAdmin()");
        SceneManager.showAdminLogin();
    }
}
