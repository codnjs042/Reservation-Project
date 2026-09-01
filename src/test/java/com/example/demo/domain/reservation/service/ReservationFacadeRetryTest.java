package com.example.demo.domain.reservation.service;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.dto.ReservationCreateRequest;
import com.example.demo.domain.reservation.dto.ReservationCreateResponse;
import com.example.demo.domain.schedule.service.ScheduleService;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.store.service.StoreService;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.domain.StoreTableStatus;
import com.example.demo.domain.storeTable.service.StoreTableService;
import com.example.demo.domain.user.domain.User;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationFacadeRetryTest {

    @Mock
    private ReservationService reservationService;
    @Mock
    private StoreService storeService;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private StoreTableService storeTableService;

    @InjectMocks
    private ReservationFacade reservationFacade;

    @Test
    void reserve_firstCandidateConflicts_retriesNextCandidate() {
        Store store = Store.builder().build();
        StoreTable tableA = StoreTable.builder().store(store).tableName("A").minCapacity(1).maxCapacity(2).status(StoreTableStatus.ACTIVE).build();
        StoreTable tableB = StoreTable.builder().store(store).tableName("B").minCapacity(1).maxCapacity(2).status(StoreTableStatus.ACTIVE).build();
        User user = User.builder().build();
        ReservationCreateRequest dto = new ReservationCreateRequest("테스트", 2, LocalDateTime.now().plusDays(1));
        Reservation reservation = Reservation.builder()
                .name(dto.name())
                .headCount(dto.headCount())
                .targetDateTime(dto.targetDateTime())
                .build();

        when(storeService.findById(1L)).thenReturn(store);
        when(storeTableService.findFreeTables(any(), any(), anyInt())).thenReturn(List.of(tableA, tableB));
        // 첫 후보(tableA)는 다른 요청이 먼저 선점했다고 가정 — 유니크 제약 위반
        when(reservationService.registerNewTransaction(user, store, tableA, dto))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(reservationService.registerNewTransaction(user, store, tableB, dto))
                .thenReturn(reservation);

        ReservationCreateResponse response = reservationFacade.reserve(user, 1L, dto);

        assertThat(response.name()).isEqualTo("테스트");
        verify(reservationService).registerNewTransaction(user, store, tableA, dto);
        verify(reservationService).registerNewTransaction(user, store, tableB, dto);
    }

    @Test
    void reserve_allCandidatesConflict_throwsReservationFullTime() {
        Store store = Store.builder().build();
        StoreTable tableA = StoreTable.builder().store(store).tableName("A").minCapacity(1).maxCapacity(2).status(StoreTableStatus.ACTIVE).build();
        User user = User.builder().build();
        ReservationCreateRequest dto = new ReservationCreateRequest("테스트", 2, LocalDateTime.now().plusDays(1));

        when(storeService.findById(1L)).thenReturn(store);
        when(storeTableService.findFreeTables(any(), any(), anyInt())).thenReturn(List.of(tableA));
        when(reservationService.registerNewTransaction(user, store, tableA, dto))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // 후보를 전부 시도했는데도 다 실패하면, DataIntegrityViolationException을 그대로 노출하지 않고
        // 도메인 예외(RESERVATION_FULL_TIME)로 변환해서 던져야 함
        assertThatThrownBy(() -> reservationFacade.reserve(user, 1L, dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_FULL_TIME);
    }
}
