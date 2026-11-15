package com.hkust.marketplace.repositories;

import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Database access for deals (offers / borrow requests / claims) between a buyer and a seller.
 * <p>
 * Functions are intentionally left for the next step. Planned methods:
 * <ul>
 *   <li>create(Deal) - insert an offer/borrow/claim in {@code PENDING} state</li>
 *   <li>findById(String) / findByBuyer(String) / findBySeller(String) / findByListing(String)</li>
 *   <li>updateStatus(id, status, actor) - accept, decline, counter, complete, cancel, return</li>
 *   <li>findActiveForListing(String) - list offers that block listing edit/delete</li>
 * </ul>
 * Column mapping notes: {@code dealType} is an ENUM ('BOUGHT','BORROWED','RECEIVED')
 * and {@code status} is an ENUM ('CANCELLED','RETURNED','ACTIVE','COMPLETED'); the
 * mapping to {@link Deal#getDealType()} ({@link ListingType}) and the string statuses in
 * the model must be defined here when the methods are implemented.
 */
public class DealRepository {
}
