package com.example.demo.domain.reservation.repository;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.domain.ReservationStatus;
import com.example.demo.domain.schedule.domain.ScheduleStatus;
import com.example.demo.domain.storeTable.domain.StoreTableStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    //가게 예약 목록 조회
    @Query(
            value = """
                    select r from Reservation r
                    join fetch r.storeTable
                    where (:keyword is null or :keyword='' or
                        (:type='id' and cast(r.id as string) like %:keyword%) or
                        (:type='name' and r.name like %:keyword%))
                    and r.store.id = :storeId
                    and cast(r.targetDateTime as date) between :startDate and :endDate
                    and (:status is null or r.status in :status)
                    """,
            countQuery = """
                    select count(r) from Reservation r
                    where (:keyword is null or :keyword='' or
                        (:type='id' and cast(r.id as string) like %:keyword%) or
                        (:type='name' and r.name like %:keyword%))
                    and r.store.id = :storeId
                    and cast(r.targetDateTime as date) between :startDate and :endDate
                    and (:status is null or r.status in :status)
                    """
    )
    Page<Reservation> getStoreReservationList(
                @Param("type") String type,
                @Param("keyword") String keyword,
                @Param("storeId") Long storeId,
                @Param("startDate") LocalDate startDate,
                @Param("endDate") LocalDate endDate,
                @Param("status") List<ReservationStatus> status,
                Pageable pageable);

    //유저 예약 목록 조회
    @Query(
            value = """
                    select r from Reservation r
                    join fetch r.store
                    where r.user.id = :userId
                    and cast(r.targetDateTime as date) between :startDate and :endDate
                    and (:status is null or r.status in :status)
                    """,
            countQuery = """
                    select count(r) from Reservation r
                    where r.user.id = :userId
                    and cast(r.targetDateTime as date) between :startDate and :endDate
                    and (:status is null or r.status in :status)
                    """
    )
    Page<Reservation> getMyReservationList(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") List<ReservationStatus> status,
            Pageable pageable);

    //예약 마감 시간대 조회
    @Query("""
            select r.targetDateTime from Reservation r
            where r.store.id = :storeId
            and r.targetDateTime between :start and :end
            and r.status = :reservationStatus
            group by r.targetDateTime
            having count(r.storeTable.id) >= (
                select count(t.id) from StoreTable t
                where t.store.id = :storeId
                and :headCount between t.minCapacity and t.maxCapacity
                and t.status = :storeTableStatus)
            """)
    List<LocalDateTime> findFullTimes(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("headCount") int headCount,
            @Param("storeTableStatus") StoreTableStatus storeTableStatus);

    //영업 시간대 변경 시 충돌 가능성이 있는 기존 예약 목록 조회
    @Query("""
            select r from Reservation r
            where r.store.id = :storeId
            and r.status = :reservationStatus
            and r.dayOfWeek= :dayOfWeek
            and exists (
                select 1 from Schedule s
                where s.store.id = :storeId
                and s.dayOfWeek = :dayOfWeek
                and cast(r.targetDateTime as time) between s.startTime and s.endTime
                and s.status = :scheduleStatus)
            """)
    List<Reservation> validateTime(
            @Param("storeId") Long storeId,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("dayOfWeek") DayOfWeek dayOfWeek,
            @Param("scheduleStatus") ScheduleStatus scheduleStatus);

    //테이블 가용 인원 변경 시 충돌 가능성이 있는 기존 예약 목록 조회
    @Query("""
            select r from Reservation r
            where r.store.id = :storeId
            and (r.headCount < :newMinCapacity or r.headCount > :newMaxCapacity)
            and r.status = :reservationStatus
            and exists (
                select 1 from StoreTable s
                where s.id = r.storeTable.id
                and s.store.id = :storeId
                and s.tableName = :oldTableName
                and s.status = :storeTableStatus)
            """)
    List<Reservation> validateCapacity(
            @Param("storeId") Long storeId,
            @Param("newMinCapacity") int newMinCapacity,
            @Param("newMaxCapacity") int newMaxCapacity,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("oldTableName") String oldTableName,
            @Param("storeTableStatus") StoreTableStatus storeTableStatus);

    @Modifying
    @Query("""
            update Reservation r
            set r.status = :status
            where r.id in :reservationIds
            """)
    void bulkUpdateStatus(
            @Param("reservationIds") List<Long> reservationIds,
            @Param("status") ReservationStatus status);


    @Query("""
            select count(r)>0 from Reservation r
            where r.store.id = :storeId
            and r.targetDateTime > :targetDateTime
            and r.status = :status
            """)
    boolean hasReservation(
            @Param("storeId") Long storeId,
            @Param("targetDateTime") LocalDateTime targetDateTime,
            @Param("status") ReservationStatus status);

    @Query(
            value = """
                    select r from Reservation r
                    join fetch r.user
                    join fetch r.store
                    where (:keyword is null or :keyword='' or
                        (cast(r.id as string) like %:keyword%) or
                        (r.user.username like %:keyword%) or
                        (r.name like %:keyword%) or
                        (r.store.name like %:keyword%))
                    and (:status is null or r.status = :status)
                    """,
            countQuery = """
                    select count(r) from Reservation r
                    where (:keyword is null or :keyword='' or
                        (cast(r.id as string) like %:keyword%) or
                        (r.user.username like %:keyword%) or
                        (r.name like %:keyword%) or
                        (r.store.name like %:keyword%))
                    and (:status is null or r.status = :status)
                    """
    )
    Page<Reservation> getReservationsForAdmin(
            @Param("keyword") String keyword,
            @Param("status") ReservationStatus status,
            Pageable pageable
    );

    @Query("""
            select r from Reservation r
            join fetch r.user
            join fetch r.store
            join fetch r.storeTable
            where r.id = :id
            """)
    Optional<Reservation> findByIdForAdmin(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value="3000")})
    @Query("""
            select r from Reservation r
            where r.id = :id
            """)
    Optional<Reservation> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value="3000")})
    @Query("""
            select r from Reservation r
            where r.id in :ids
            """)
    List<Reservation> findAllByIdWithLock(@Param("ids") List<Long> ids);

    List<Reservation> findByStoreIdAndTargetDateTime(Long storeId, LocalDateTime targetDateTime);

    @Transactional
    void deleteByStoreIdAndTargetDateTime(Long storeId, LocalDateTime targetDateTime);
}
