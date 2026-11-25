package com.hkust.marketplace.services;

import com.hkust.marketplace.models.DealStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.DealRepository;
import com.hkust.marketplace.repositories.NotificationRepository;
import com.hkust.marketplace.repositories.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;

import static com.hkust.marketplace.models.DealStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class BuyerDealServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private final User buyer = new User("buyer", "Buyer", "unused", UserRole.BUYER);
    private final BuyerDealService service = new BuyerDealService(
            new DealRepository(), new UserRepository(), new NotificationRepository(),
            () -> { throw new SQLException("reached persistence"); }, CLOCK);

    @Test void borrowRequestRequiresAtLeastFiveSeconds() {
        LocalDateTime start = NOW.plusSeconds(1);
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(buyer, "listing", ListingType.LEND, null, start, start.plusSeconds(4)));
        assertThrows(SQLException.class, () ->
                service.submit(buyer, "listing", ListingType.LEND, null, start, start.plusSeconds(5)));
    }

    @ParameterizedTest
    @EnumSource(DealStatus.class)
    void buyerDealLifecycleAllowsOnlyValidNextStates(DealStatus status) {
        var allowed = switch (status) {
            case PENDING, COUNTERED -> EnumSet.of(COUNTERED, ACCEPTED, DECLINED, CANCELLED);
            case ACCEPTED -> EnumSet.of(COMPLETED, RETURNED, CANCELLED);
            default -> EnumSet.noneOf(DealStatus.class);
        };
        for (var next : DealStatus.values()) {
            assertEquals(allowed.contains(next), status.canTransitionTo(next), status + " to " + next);
        }
        assertFalse(status.canTransitionTo(null));
        assertEquals(!allowed.isEmpty(), status.isOpen());
    }

    @Test void borrowRequestCannotStartInThePast() {
        LocalDateTime start = NOW.minusSeconds(1);
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(buyer, "listing", ListingType.LEND, null, start, start.plusMinutes(1)));
    }
}
