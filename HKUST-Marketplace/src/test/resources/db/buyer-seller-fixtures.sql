-- Test-only accounts for the disposable MySQL fixtures.
INSERT IGNORE INTO users
    (username, full_name, password_hash, role,
     residence_hall, bio, preferred_meetup, active)
VALUES
    ('alice',   'Alice Chan', '$argon2id$v=19$m=19456,t=2,p=1$aZ1KIYfT1gSgVyl24sLYzw$xSPGD8I+HbBA0ZkB26Bet86bpWEU0PSdiFtBsioUyZk', 'BUYER', 'Hall 3', NULL, NULL, TRUE),
    ('bob',     'Bob Wong', '$argon2id$v=19$m=19456,t=2,p=1$6w1dLgOqHE5N7fp1c0MhLQ$DCCz4KqF9b+qzJlSw4CIQE0IwrkH197OuzfEhUvyJZw', 'BUYER', 'Off-campus', NULL, NULL, TRUE),
    ('charlie', 'Charlie Lee', '$argon2id$v=19$m=19456,t=2,p=1$UV8Td+PqbVd0ktsDMjrk+A$SnWIPrnQNK+RR4o5lRXhpQ3ZsciS9Oh3cNm4hTnTjJw', 'SELLER', NULL,
     'Final year CSE student selling textbooks and gadgets.', 'Library', TRUE),
    ('diana',   'Diana Ng', '$argon2id$v=19$m=19456,t=2,p=1$7+V42kBf0v3aAqxwmBayyQ$SBsvvJ/pDs3ZPNtEvFGIKVrtGuSZBfTUVj1WBM70K2M', 'SELLER', NULL,
     'Selling furniture and kitchen items before graduation.', 'Hall 5 Lobby', TRUE);
