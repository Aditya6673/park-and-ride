-- V4__seed_sample_lots.sql
-- Seeds 3 sample parking lots and their slots for development/testing.
-- Safe to run multiple times (ON CONFLICT DO NOTHING).

INSERT INTO parking_lots (id, name, address, city, state, latitude, longitude,
                          total_slots, contact_phone, description, is_active)
VALUES
    ('a1000000-0000-0000-0000-000000000001',
     'Connaught Place Parking Block A',
     'Block A, Connaught Place', 'New Delhi', 'Delhi',
     28.6315, 77.2167, 20, '+91-11-2345-6789',
     'Multi-level parking near CP metro station. EV charging available.', TRUE),

    ('a1000000-0000-0000-0000-000000000002',
     'Koramangala Tech Park Parking',
     '80 Feet Road, Koramangala', 'Bengaluru', 'Karnataka',
     12.9279, 77.6271, 30, '+91-80-4567-8901',
     'Open-air parking adjacent to tech park campus. 24/7 security.', TRUE),

    ('a1000000-0000-0000-0000-000000000003',
     'Bandra Station Parking Complex',
     'Station Road, Bandra West', 'Mumbai', 'Maharashtra',
     19.0596, 72.8295, 25, '+91-22-6789-0123',
     'Covered parking 200m from Bandra railway station.', TRUE)
ON CONFLICT (id) DO NOTHING;

-- Slots for Lot 1 (Connaught Place) — 5 CAR + 3 BIKE + 2 EV
INSERT INTO parking_slots (lot_id, slot_number, slot_type, price_per_hour, floor, position_index)
SELECT 'a1000000-0000-0000-0000-000000000001', slot_number, slot_type, price, floor, pos
FROM (VALUES
    ('A-001','CAR',  50.00, 'G', 1),
    ('A-002','CAR',  50.00, 'G', 2),
    ('A-003','CAR',  50.00, 'G', 3),
    ('A-004','CAR',  45.00, '1', 1),
    ('A-005','CAR',  45.00, '1', 2),
    ('A-006','BIKE', 15.00, 'G', 1),
    ('A-007','BIKE', 15.00, 'G', 2),
    ('A-008','BIKE', 15.00, 'G', 3),
    ('A-009','EV',   80.00, 'G', 1),
    ('A-010','EV',   80.00, 'G', 2)
) AS t(slot_number, slot_type, price, floor, pos)
ON CONFLICT DO NOTHING;

-- Slots for Lot 2 (Koramangala) — 10 CAR + 5 BIKE + 3 EV + 2 DISABLED
INSERT INTO parking_slots (lot_id, slot_number, slot_type, price_per_hour, floor, position_index)
SELECT 'a1000000-0000-0000-0000-000000000002', slot_number, slot_type, price, floor, pos
FROM (VALUES
    ('K-001','CAR',    40.00,'G',1), ('K-002','CAR',    40.00,'G',2),
    ('K-003','CAR',    40.00,'G',3), ('K-004','CAR',    40.00,'G',4),
    ('K-005','CAR',    40.00,'G',5), ('K-006','CAR',    35.00,'1',1),
    ('K-007','CAR',    35.00,'1',2), ('K-008','CAR',    35.00,'1',3),
    ('K-009','CAR',    35.00,'1',4), ('K-010','CAR',    35.00,'1',5),
    ('K-011','BIKE',   12.00,'G',1), ('K-012','BIKE',   12.00,'G',2),
    ('K-013','BIKE',   12.00,'G',3), ('K-014','BIKE',   12.00,'G',4),
    ('K-015','BIKE',   12.00,'G',5), ('K-016','EV',     70.00,'G',1),
    ('K-017','EV',     70.00,'G',2), ('K-018','EV',     70.00,'G',3),
    ('K-019','DISABLED',0.00,'G',1), ('K-020','DISABLED',0.00,'G',2)
) AS t(slot_number, slot_type, price, floor, pos)
ON CONFLICT DO NOTHING;
