package com.example.demo.domain.reservation.service;

import com.example.demo.domain.reservation.domain.Reservation;
import com.example.demo.domain.reservation.dto.ReservationCreateRequest;
import com.example.demo.domain.reservation.repository.ReservationRepository;
import com.example.demo.domain.user.domain.User;
import com.example.demo.domain.user.repository.UserRepository;
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

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=false"})
public class ReservationFacadeTest {
    @Autowired
    private ReservationFacade reservationFacade;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    public void tearDown() {
        reservationRepository.deleteByStoreIdAndTargetDateTime(1L, LocalDateTime.of(2027, 2, 1, 12, 0));
    }

    @Test
    public void reserve_concurrentRequests_noDuplicateReservation() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        User user = userRepository.findByUsernameAndDeletedVersion("test0000", 0L).get();
        ReservationCreateRequest dto = new ReservationCreateRequest(
                "테스트", 2, LocalDateTime.of(2027, 2, 1, 12, 0)
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

        List<Reservation> reservations = reservationRepository.findByStoreIdAndTargetDateTime(1L, LocalDateTime.of(2027, 2, 1, 12, 0));

        assertThat(reservations).hasSize(20);

        long distinct = reservations.stream()
                .map(r -> r.getStoreTable().getId())
                .distinct()
                .count();
        assertThat(reservations.size()).isEqualTo(distinct);
    }
}
