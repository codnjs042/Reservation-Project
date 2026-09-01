package com.example.demo.domain.reservation.service;

import com.example.demo.domain.admin.dto.ReservationAdminDetailResponse;
import com.example.demo.domain.admin.dto.ReservationAdminResponse;
import com.example.demo.domain.owner.dto.ReservationSearchOwnerRequest;
import com.example.demo.domain.owner.dto.ReservationUpdateOwnerRequest;
import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.domain.ReservationStatus;
import com.example.demo.domain.reservation.dto.*;
import com.example.demo.domain.reservation.repository.ReservationRepository;
import com.example.demo.domain.schedule.domain.ScheduleStatus;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.domain.StoreTableStatus;
import com.example.demo.domain.user.domain.User;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {
    private final ReservationRepository reservationRepository;

    //새로운 독립된 트랜잭션 생성
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation registerNewTransaction(User user, Store store, StoreTable storeTable, ReservationCreateRequest dto){
        Reservation reservation = Reservation.builder()
                .user(user)
                .name(dto.name())
                .store(store)
                .targetDateTime(dto.targetDateTime())
                .dayOfWeek(dto.targetDateTime().getDayOfWeek())
                .headCount(dto.headCount())
                .storeTable(storeTable)
                .status(ReservationStatus.CONFIRMED)
                .build();

        reservationRepository.save(reservation);

        return reservation;
    }

    public Reservation findById(Long reservationId){
        return reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    public Set<LocalTime> getFullSlots(Long storeId, LocalDate targetDate, int headCount){
        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.atTime(LocalTime.MAX);

        return reservationRepository.findFullTimes(storeId, start, end, ReservationStatus.CONFIRMED, headCount, StoreTableStatus.ACTIVE)
                .stream()
                .map(LocalDateTime::toLocalTime)
                .collect(Collectors.toSet());
    }

    public Page<Reservation> getStoreReservation(Long storeId, ReservationSearchOwnerRequest dto, LocalDate startDate, LocalDate endDate){
        PageRequest pageable = PageRequest.of(dto.page(), dto.size(), Sort.by("targetDateTime").descending());
        return reservationRepository.getStoreReservationList(dto.type(), dto.keyword(), storeId, startDate, endDate, dto.status(), pageable);
    }

    public Page<ReservationSearchResponse> getMyReservation(Long userId, ReservationSearchRequest dto){
        LocalDate startDate = dto.startDate()==null ? LocalDate.now().minusMonths(1) : dto.startDate();
        LocalDate endDate = dto.endDate()==null ? LocalDate.now().plusMonths(1) : dto.endDate();
        validateDate(startDate, endDate);

        PageRequest pageable = PageRequest.of(dto.page(), dto.size(), Sort.by("targetDateTime").descending());
        return reservationRepository.getMyReservationList(userId, startDate, endDate, dto.status(), pageable)
                .map(ReservationSearchResponse::from);
    }

    public void validateOwner(Reservation reservation, Long userId){
        if(!reservation.getStore().getOwner().getId().equals(userId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void validateGuest(Reservation reservation, Long userId){
        if(!reservation.getUser().getId().equals(userId))
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    public void validateBeforeReservation(Reservation reservation){
        LocalDateTime deadline = reservation.getTargetDateTime().toLocalDate().atStartOfDay();

        if (deadline.isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATUS);

        if(reservation.getStatus()!=ReservationStatus.CONFIRMED)
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    public void validateBeforeOwnerReservation(Reservation reservation){
        LocalDateTime deadline = reservation.getTargetDateTime();

        if (deadline.isBefore(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATUS);

        if(reservation.getStatus()!=ReservationStatus.CONFIRMED)
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    public void validateAfterReservation(Reservation reservation){
        if(reservation.getTargetDateTime().isAfter(LocalDateTime.now()))
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    @Transactional
    public void cancelReservation(Long userId, Long reservationId){
        Reservation reservation = findById(reservationId);
        validateGuest(reservation, userId);
        validateBeforeReservation(reservation);
        reservation.updateStatus(ReservationStatus.CANCELED);
    }

    public List<Reservation> findAllById(List<Long> reservationIds){
        return reservationRepository.findAllByIdWithLock(reservationIds);
    }

    @Transactional
    public void updateStatus(Long userId, ReservationUpdateOwnerRequest dto){
        List<Reservation> reservations = findAllById(dto.ids());
        reservations.forEach(reservation -> {
            validateOwner(reservation, userId);
            switch(dto.status()){
                case REJECTED -> validateBeforeOwnerReservation(reservation);
                case VISITED, NO_SHOW -> validateAfterReservation(reservation);
            }
        });
        reservationRepository.bulkUpdateStatus(dto.ids(), dto.status());
    }

    public void validateTime(Long storeId, DayOfWeek dayOfWeek){
        List<Reservation> reservations = reservationRepository.validateTime(storeId, ReservationStatus.CONFIRMED, dayOfWeek, ScheduleStatus.ACTIVE);
        if(!reservations.isEmpty())
            throw new BusinessException(ErrorCode.SCHEDULE_LOCKED);
    }

    public void validateCapacity(Long storeId, int minCapacity, int maxCapacity, String tableName){
        List<Reservation> reservations = reservationRepository.validateCapacity(storeId, minCapacity, maxCapacity, ReservationStatus.CONFIRMED, tableName, StoreTableStatus.ACTIVE);
        if(!reservations.isEmpty())
            throw new BusinessException(ErrorCode.TABLE_LOCKED);
    }

    public void validateDeletable(Long storeId){
        boolean hasReservation = reservationRepository.hasReservation(storeId, LocalDateTime.now(), ReservationStatus.CONFIRMED);
        if(hasReservation)
            throw new BusinessException(ErrorCode.STORE_LOCKED);
    }

    public ReservationAdminDetailResponse findByIdForAdmin(Long reservationId){
        Reservation reservation = reservationRepository.findByIdForAdmin(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        return ReservationAdminDetailResponse.from(reservation);
    }

    public Page<ReservationAdminResponse> getReservationsForAdmin(String keyword, ReservationStatus status, Pageable pageable){
        return reservationRepository.getReservationsForAdmin(keyword, status, pageable)
                .map(ReservationAdminResponse::from);
    }

    public void validateDate(LocalDate startDate, LocalDate endDate){
        if(startDate.isAfter(endDate))
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_DATE);
    }
}
