package com.example.demo.domain.reservation.service;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.domain.ReservationStatus;
import com.example.demo.domain.reservation.dto.ReservationCreateRequest;
import com.example.demo.domain.reservation.dto.ReservationCreateResponse;
import com.example.demo.domain.reservation.repository.ReservationRepository;
import com.example.demo.domain.user.domain.User;
import com.example.demo.domain.user.repository.UserRepository;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=false"})
public class ReservationFacadeTest {
    @Autowired
    private ReservationFacade reservationFacade;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    private static final LocalDateTime CONCURRENT_SLOT = LocalDateTime.of(2027, 2, 1, 12, 0);
    private static final LocalDateTime CANCEL_REBOOK_SLOT = LocalDateTime.of(2027, 2, 2, 12, 0);
    private static final LocalDateTime EXHAUSTION_SLOT = LocalDateTime.of(2027, 2, 3, 12, 0);

    @AfterEach
    public void tearDown() {
        reservationRepository.deleteByStoreIdAndTargetDateTime(1L, CONCURRENT_SLOT);
        reservationRepository.deleteByStoreIdAndTargetDateTime(1L, CANCEL_REBOOK_SLOT);
        reservationRepository.deleteByStoreIdAndTargetDateTime(1L, EXHAUSTION_SLOT);
    }

    @Test
    public void reserve_concurrentRequests_noDuplicateReservation() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        User user = userRepository.findByUsernameAndDeletedVersion("test0001", 0L).get();
        ReservationCreateRequest dto = new ReservationCreateRequest(
                "테스트", 2, CONCURRENT_SLOT
        );

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reservationFacade.reserve(user, 1L, dto);
                }
                catch(Exception e){
                }
                finally{
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();

        List<Reservation> reservations = reservationRepository.findByStoreIdAndTargetDateTime(1L, CONCURRENT_SLOT);

        assertThat(reservations).hasSize(20);

        long distinct = reservations.stream()
                .map(r -> r.getStoreTable().getId())
                .distinct()
                .count();
        assertThat(reservations.size()).isEqualTo(distinct);
    }

    @Test
    public void reserve_afterCancellation_sameSlotCanBeReservedAgain() {
        User user = userRepository.findByUsernameAndDeletedVersion("test0001", 0L).get();
        ReservationCreateRequest dto = new ReservationCreateRequest(
                "테스트", 2, CANCEL_REBOOK_SLOT
        );

        ReservationCreateResponse first = reservationFacade.reserve(user, 1L, dto);
        reservationService.cancelReservation(user.getId(), first.id());

        ReservationCreateResponse second = reservationFacade.reserve(user, 1L, dto);

        assertThat(second.id()).isNotEqualTo(first.id());
        List<Reservation> reservations = reservationRepository.findByStoreIdAndTargetDateTime(1L, CANCEL_REBOOK_SLOT);
        assertThat(reservations).hasSize(2);
        assertThat(reservations.stream().filter(r -> r.getStatus() == ReservationStatus.CONFIRMED).count())
                .isEqualTo(1);
    }

    @Test
    public void reserve_afterAllTablesBooked_throwsReservationFullTime() {
        User user = userRepository.findByUsernameAndDeletedVersion("test0001", 0L).get();
        ReservationCreateRequest dto = new ReservationCreateRequest(
                "테스트", 2, EXHAUSTION_SLOT
        );

        for (int i = 0; i < 20; i++) {
            reservationFacade.reserve(user, 1L, dto);
        }

        assertThatThrownBy(() -> reservationFacade.reserve(user, 1L, dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_FULL_TIME);
    }
}
