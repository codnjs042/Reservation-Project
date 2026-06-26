package com.example.demo.domain.reservation.service;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.dto.*;
import com.example.demo.domain.schedule.service.ScheduleService;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.store.service.StoreService;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.service.StoreTableService;
import com.example.demo.domain.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    @Transactional
    public ReservationCreateResponse reserve(User user, Long storeId, ReservationCreateRequest dto){
        Store store = storeService.findById(storeId);

        //단체 예약 여부
        storeTableService.validateGroup(store.getId(), dto.headCount());

        //특정 요일의 예약 시간대 확인
        scheduleService.validateAvailableTime(store.getId(), dto.targetDateTime());

        //예약 가능 테이블 확인 & 테이블 배정
        StoreTable storeTable = storeTableService.matchTable(store.getId(), dto.targetDateTime(), dto.headCount());

        //예약
        Reservation reservation = reservationService.register(user, store, storeTable, dto);

        return ReservationCreateResponse.from(reservation);
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
