package com.parkride.parking.repository;

import com.parkride.parking.domain.ParkingLot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingLotRepository extends JpaRepository<ParkingLot, UUID> {

    Page<ParkingLot> findByCityIgnoreCaseAndActiveTrue(String city, Pageable pageable);

    List<ParkingLot> findByActiveTrueOrderByNameAsc();

    /**
     * Finds lots within {@code radiusKm} kilometres of the given coordinates,
     * ordered by distance ascending (nearest first), with full pagination support.
     *
     * <p>A separate {@code countQuery} is required because Spring Data cannot
     * derive a COUNT query from a JPQL {@code ORDER BY} that uses a computed
     * expression — it would try to wrap it and fail with a syntax error.
     *
     * @param lat       centre latitude (WGS84)
     * @param lng       centre longitude (WGS84)
     * @param radiusKm  search radius in kilometres
     * @param pageable  page/size/sort (sort is ignored — distance order is fixed)
     */
    @Query(value = """
            SELECT l FROM ParkingLot l
            WHERE l.active = true
            AND (6371 * acos(
                  cos(radians(:lat)) * cos(radians(l.latitude)) *
                  cos(radians(l.longitude) - radians(:lng)) +
                  sin(radians(:lat)) * sin(radians(l.latitude))
            )) <= :radiusKm
            ORDER BY (6371 * acos(
                  cos(radians(:lat)) * cos(radians(l.latitude)) *
                  cos(radians(l.longitude) - radians(:lng)) +
                  sin(radians(:lat)) * sin(radians(l.latitude))
            )) ASC
            """,
           countQuery = """
            SELECT COUNT(l) FROM ParkingLot l
            WHERE l.active = true
            AND (6371 * acos(
                  cos(radians(:lat)) * cos(radians(l.latitude)) *
                  cos(radians(l.longitude) - radians(:lng)) +
                  sin(radians(:lat)) * sin(radians(l.latitude))
            )) <= :radiusKm
            """)
    Page<ParkingLot> findNearby(@Param("lat") double lat,
                                @Param("lng") double lng,
                                @Param("radiusKm") double radiusKm,
                                Pageable pageable);
}
