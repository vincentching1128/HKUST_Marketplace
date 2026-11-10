package com.hkust.marketplace.services;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.repositories.ListingRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class ListingService {

    private final UserRepository userRepository = new UserRepository();
    private boolean checkAccountStatus(User user) throws SQLException {
        if (user == null) return false;

        User current = userRepository.findByUsername(user.getUsername());

        return current != null
                && current.isActive()
                && current.getRole() == UserRole.SELLER;
    }
}
