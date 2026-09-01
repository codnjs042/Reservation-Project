package com.example.demo.domain.reservation.service;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.dto.*;
import com.example.demo.domain.schedule.service.ScheduleService;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.store.service.StoreService;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.service.StoreTableService;
import com.example.demo.domain.user.domain.User;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationFacade {
    private final ReservationService reservationService;
    private final StoreService storeService;
    private final ScheduleService scheduleService;
    private final StoreTableService storeTableService;

    //트랜잭션 취소
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReservationCreateResponse reserve(User user, Long storeId, ReservationCreateRequest dto){
        Store store = storeService.findById(storeId);

        //단체 예약 여부
        storeTableService.validateGroup(store.getId(), dto.headCount());

        //특정 요일의 예약 시간대 확인
        scheduleService.validateAvailableTime(store.getId(), dto.targetDateTime());

        //예약 가능 테이블 후보 (수용인원 오름차순, 락 없음)
        List<StoreTable> candidates = storeTableService.findFreeTables(store.getId(), dto.targetDateTime(), dto.headCount());

        //후보를 순서대로 시도, 유니크 제약 위반(다른 요청이 먼저 선점) 시 다음 후보로 재시도
        for (StoreTable candidate : candidates) {
            try {
                Reservation reservation = reservationService.registerNewTransaction(user, store, candidate, dto);
                return ReservationCreateResponse.from(reservation);
            } catch (DataIntegrityViolationException e) {
                log.info("테이블 {} 선점 경합으로 예약 실패, 다음 후보로 재시도", candidate.getId());
            }
        }

        throw new BusinessException(ErrorCode.RESERVATION_FULL_TIME);
    }

    public List<ReservationTimeSlotResponse> getTimeSlots(Long storeId, ReservationTimeSlotRequest dto) {
        Store store = storeService.findById(storeId);

        //단체 예약 여부
        storeTableService.validateGroup(store.getId(), dto.headCount());

        //과거 날짜 여부
        reservationService.validateDate(LocalDate.now(), dto.targetDate());

        //특정 요일의 운영 시간표
        List<LocalTime> allTimes = scheduleService.generateSlots(store.getId(), dto.targetDate().getDayOfWeek());

        //특정 날짜의 예약 마감 시간대
        Set<LocalTime> fullTimes = reservationService.getFullSlots(store.getId(), dto.targetDate(), dto.headCount());

        //특정 날짜의 운영 시간대별 예약 현황
        return allTimes.stream()
                .filter(t -> {
                    if(dto.targetDate().isEqual(LocalDate.now())) {
                        return t.isAfter(LocalTime.now());
                    }
                    return true;
                })
                .map(t -> new ReservationTimeSlotResponse(t, !fullTimes.contains(t)))
                .toList();
    }
}
