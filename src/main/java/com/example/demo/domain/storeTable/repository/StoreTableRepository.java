package com.example.demo.domain.storeTable.repository;

import com.example.demo.domain.reservation.domain.ReservationStatus;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.domain.StoreTableStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreTableRepository extends JpaRepository<StoreTable, Long> {
    @Query("""
            select s from StoreTable s
            where s.store.id = :storeId
            and s.status = :status
            """)
    List<StoreTable> findAllTables(
            @Param("storeId") Long storeId,
            @Param("status") StoreTableStatus status);

    @Query("""
            select s from StoreTable s
            where s.store.id = :storeId
            and s.tableName = :tableName
            and s.status = :status
            """)
    List<StoreTable> findTableGroup(
            @Param("storeId") Long storeId,
            @Param("tableName") String tableName,
            @Param("status") StoreTableStatus status);

    //동일 테이블 존재 여부
    boolean existsByStore_IdAndTableNameAndStatus(
            Long storeId,
            String tableName,
            StoreTableStatus status);

    //예약 가능 테이블 현황
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("""
            select t from StoreTable t
            where t.store.id = :storeId
            and :headCount between t.minCapacity and t.maxCapacity
            and t.status = :storeTableStatus
            and not exists (
                select 1 from Reservation r
                where r.targetDateTime = :targetDateTime
                and r.storeTable.id = t.id
                and r.status = :reservationStatus)
            order by t.maxCapacity asc
            limit 1
            """)
    List<StoreTable> findFreeTableWithLock(
            @Param("storeId") Long storeId,
            @Param("targetDateTime") LocalDateTime targetDateTime,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("headCount") int headCount,
            @Param("storeTableStatus") StoreTableStatus storeTableStatus);

    //수용 가능한 테이블 존재 여부
    boolean existsByStore_IdAndMaxCapacityGreaterThanEqualAndStatus(
            Long storeId,
            int headCount,
            StoreTableStatus status);

    //특정 테이블 그룹 내 기존 예약이 배정되지 않은 테이블 목록 조회
    @Query("""
            select s from StoreTable s
            where s.store.id = :storeId
            and s.tableName = :tableName
            and s.status = :storeTableStatus
            and not exists (
                select 1 from Reservation r
                where r.store.id = :storeId
                and s.id = r.storeTable.id
                and r.status = :reservationStatus)
           """)
    List<StoreTable> findSafeTables(
            @Param("storeId") Long storeId,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("tableName") String tableName,
            @Param("storeTableStatus") StoreTableStatus storeTableStatus);

    boolean existsByStoreIdAndStatus(Long storeId, StoreTableStatus status);

    @Modifying
    @Query("""
            update StoreTable t
            set t.status = :status
            where t.id in :storeIds
            """)
    void bulkUpdateStatus(
            @Param("storeIds") List<Long> storeIds,
            @Param("status") StoreTableStatus status);
}
