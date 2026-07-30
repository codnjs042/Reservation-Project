package com.example.demo.global.init;

import com.example.demo.domain.schedule.dto.ScheduleUpsertRequest;
import com.example.demo.domain.schedule.service.ScheduleService;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.store.domain.StoreCategory;
import com.example.demo.domain.store.domain.StoreStatus;
import com.example.demo.domain.store.repository.StoreRepository;
import com.example.demo.domain.storeTable.service.StoreTableService;
import com.example.demo.domain.user.domain.User;
import com.example.demo.domain.user.domain.UserLoginType;
import com.example.demo.domain.user.domain.UserRole;
import com.example.demo.domain.user.domain.UserStatus;
import com.example.demo.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {
    private static final String STORE_SEED_CSV = "data/stores.csv";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StoreRepository storeRepository;
    private final ScheduleService scheduleService;
    private final StoreTableService storeTableService;

    private static final int TEST_USER_COUNT = 200;

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    @Value("${test.username}")
    private String loadTestUsername;

    @Value("${test.password}")
    private String loadTestPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        User admin = getOrCreateAdmin();
        createStoresIfAbsent(admin);
        createLoadTestUsersIfAbsent();
    }

    private void createLoadTestUsersIfAbsent() {
        String encodedPassword = passwordEncoder.encode(loadTestPassword);
        int created = 0;

        for (int i = 1; i <= TEST_USER_COUNT; i++) {
            String username = loadTestUsername + String.format("%04d", i);

            if (userRepository.existsByUsernameAndDeletedVersion(username, 0L)) {
                continue;
            }

            User loadTestUser = User.builder()
                    .username(username)
                    .nickname(username)
                    .password(encodedPassword)
                    .loginType(UserLoginType.LOCAL)
                    .role(UserRole.USER)
                    .status(UserStatus.ACTIVE)
                    .build();
            userRepository.save(loadTestUser);
            created++;
        }

        log.info("[AdminInitializer] 부하테스트용 유저 {}건 생성 완료 ({}0001~{}{})",
                created, loadTestUsername, loadTestUsername, String.format("%04d", TEST_USER_COUNT));
    }

    private User getOrCreateAdmin() {
        if (!userRepository.existsByRoleAndDeletedVersion(UserRole.ADMIN, 0L)) {
            User admin = User.builder()
                    .username(adminUsername)
                    .nickname("관리자")
                    .password(passwordEncoder.encode(adminPassword))
                    .loginType(UserLoginType.LOCAL)
                    .role(UserRole.ADMIN)
                    .status(UserStatus.ACTIVE)
                    .build();
            userRepository.save(admin);
            log.info("[AdminInitializer] 관리자 계정 생성 완료 (username: {})", adminUsername);
        } else {
            log.info("[AdminInitializer] 관리자 계정이 이미 존재합니다.");
        }

        return userRepository.findByUsernameAndDeletedVersion(adminUsername, 0L)
                .orElseThrow(() -> new IllegalStateException("관리자 계정 조회 실패"));
    }

    private void createStoresIfAbsent(User admin) {
        if (!storeRepository.getMyStores(admin.getId(), StoreStatus.SHUTDOWN).isEmpty()) {
            log.info("[AdminInitializer] 관리자 가게가 이미 존재합니다.");
            return;
        }

        List<StoreSeedRow> csvRows = loadStoreRowsFromCsv();
        if (csvRows.isEmpty()) {
            log.warn("[AdminInitializer] stores.csv 로드 결과 없음 — 가게 생성 생략");
            return;
        }

        createStoresFromCsvRows(admin, csvRows);
    }

    private record StoreSeedRow(String name, StoreCategory category, String address, String detailAddress,
                                 String zipCode, String sigunguCode, Double latitude, Double longitude,
                                 String phone, String ownerName, String businessNumber, StoreStatus status) {
    }

    private List<StoreSeedRow> loadStoreRowsFromCsv() {
        ClassPathResource resource = new ClassPathResource(STORE_SEED_CSV);
        if (!resource.exists()) {
            return List.of();
        }

        Map<String, StoreSeedRow> rows = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split("\t", -1);
                String name = cols[1];
                String address = cols[3];
                String detailAddress = "NULL".equals(cols[4]) ? null : cols[4];

                StoreSeedRow row = new StoreSeedRow(
                        name,
                        StoreCategory.valueOf(cols[2]),
                        address,
                        detailAddress,
                        cols[5],
                        cols[6],
                        parseDouble(cols[7]),
                        parseDouble(cols[8]),
                        cols[9],
                        cols[11],
                        cols[12],
                        StoreStatus.valueOf(cols[14]));
                rows.putIfAbsent(name + "|" + address, row);
            }
        } catch (IOException e) {
            log.error("[AdminInitializer] stores.csv 로드 실패: {}", e.getMessage());
            return List.of();
        }

        log.info("[AdminInitializer] stores.csv 로드 완료 ({}건)", rows.size());
        return List.copyOf(rows.values());
    }

    private void createStoresFromCsvRows(User admin, List<StoreSeedRow> rows) {
        for (StoreSeedRow row : rows) {
            Store store = Store.builder()
                    .name(row.name())
                    .category(row.category())
                    .address(row.address())
                    .detailAddress(row.detailAddress())
                    .zipCode(row.zipCode())
                    .sigunguCode(row.sigunguCode())
                    .latitude(row.latitude())
                    .longitude(row.longitude())
                    .phone(row.phone())
                    .owner(admin)
                    .ownerName(row.ownerName())
                    .businessNumber(row.businessNumber())
                    .status(row.status())
                    .build();

            Store saved = storeRepository.save(store);
            createSchedules(saved);
            createTables(saved);
        }

        log.info("[AdminInitializer] 가게 {}건 생성 완료 (CSV, 스케줄·테이블 포함)", rows.size());
    }

    private void createSchedules(Store store) {
        ScheduleUpsertRequest slot = new ScheduleUpsertRequest(LocalTime.of(12, 0), LocalTime.of(21, 0), 30);
        for (DayOfWeek day : DayOfWeek.values()) {
            scheduleService.upsert(store, day, List.of(slot));
        }
    }

    private void createTables(Store store) {
        storeTableService.create(store, "2인석", 1, 2, 10);
        storeTableService.create(store, "4인석", 2, 4, 10);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}