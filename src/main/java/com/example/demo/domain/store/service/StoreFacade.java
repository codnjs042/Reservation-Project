package com.example.demo.domain.store.service;

import com.example.demo.domain.favorite.service.FavoriteService;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.store.domain.StoreStatus;
import com.example.demo.domain.store.dto.StoreDetailResponse;
import com.example.demo.domain.store.dto.StoreCreateRequest;
import com.example.demo.domain.user.domain.User;
import com.example.demo.domain.user.domain.UserRole;
import com.example.demo.domain.user.service.UserService;
import com.example.demo.global.infra.kakao.KakaoLocalClient;
import com.example.demo.global.infra.kakao.PointDto;
import com.example.demo.global.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreFacade {
    private final StoreService storeService;
    private final UserService userService;
    private final SecurityUtil securityUtil;
    private final FavoriteService favoriteService;
    private final KakaoLocalClient kakaoLocalClient;

    @Transactional
    public StoreDetailResponse create(Long userId, StoreCreateRequest dto){
        PointDto coordinates = kakaoLocalClient.getCoordinates(dto.address());

        User user = userService.findByIdWithLock(userId);

        Store store = storeService.create(user, dto, coordinates);

        if(user.getRole()==UserRole.USER){
            userService.updateRole(user.getId(), UserRole.OWNER);

            User currentUser = userService.findById(user.getId());
            securityUtil.updateSecurityContext(currentUser);
        }

        return StoreDetailResponse.from(store, false);
    }

    public StoreDetailResponse getDetail(Long userId, Long storeId){
        Store store = storeService.findById(storeId);

        storeService.validateStatus(store, StoreStatus.OPEN);

        boolean isFavorite = false;
        if(userId!=null)
            isFavorite = favoriteService.existsByUser_IdAndStore_IdAndStatus(userId, store.getId());

        return StoreDetailResponse.from(store, isFavorite);
    }
}
