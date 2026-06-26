package com.example.demo.domain.storeTable.service;

import com.example.demo.domain.reservation.domain.ReservationStatus;
import com.example.demo.domain.store.domain.Store;
import com.example.demo.domain.storeTable.domain.StoreTable;
import com.example.demo.domain.storeTable.domain.StoreTableStatus;
import com.example.demo.domain.storeTable.dto.StoreTableBulkUpdateRequest;
import com.example.demo.domain.storeTable.repository.StoreTableRepository;
import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreTableService {
    private final StoreTableRepository storeTableRepository;

    @Transactional
    public void create(Store store, String tableName, int minCapacity, int maxCapacity, int count){
        List<StoreTable> storeTables = IntStream.range(0, count)
                .mapToObj(
                        i -> StoreTable.builder()
                                .store(store)
                                .tableName(tableName)
                                .minCapacity(minCapacity)
                                .maxCapacity(maxCapacity)
                                .status(StoreTableStatus.ACTIVE)
                                .build())
                .toList();

        storeTableRepository.saveAll(storeTables);
    }

    public void validateName(Long storeId, String tableName){
        boolean isExists = storeTableRepository.existsByStore_IdAndTableNameAndStatus(storeId, tableName, StoreTableStatus.ACTIVE);
        if(isExists)
            throw new BusinessException(ErrorCode.TABLE_ALREADY_EXIST);
    }

    public void validateCapacity(int minCapacity, int maxCapacity){
        if(minCapacity > maxCapacity || minCapacity <= 0)
            throw new BusinessException(ErrorCode.INVALID_TABLE_CAPACITY);
    }

    public List<StoreTable> findAllTables(Long storeId){
        return storeTableRepository.findAllTables(storeId, StoreTableStatus.ACTIVE);
    }

    public void validateGroup(Long storeId, int headCount){
        boolean isFit = storeTableRepository.existsByStore_IdAndMaxCapacityGreaterThanEqualAndStatus(storeId, headCount, StoreTableStatus.ACTIVE);
        if(!isFit)
            throw new BusinessException(ErrorCode.RESERVATION_GROUP_LIMIT);
    }

    @Transactional
    public StoreTable matchTable(Long storeId, LocalDateTime targetDateTime, int headCount){
        List<StoreTable> freeTables = storeTableRepository.findFreeTableWithLock(storeId, targetDateTime, ReservationStatus.CONFIRMED, headCount, StoreTableStatus.ACTIVE);

        return freeTables.stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_FULL_TIME));
    }

    public List<StoreTable> findTableGroup(Long storeId, String tableName){
        return storeTableRepository.findTableGroup(storeId, tableName, StoreTableStatus.ACTIVE);
    }

    public void validateCount(Long storeId, String oldTableName, int newTableCount){
        List<StoreTable> oldStoreTables = findTableGroup(storeId, oldTableName);
        List<StoreTable> unReservedTable = storeTableRepository.findSafeTables(storeId, ReservationStatus.CONFIRMED, oldTableName, StoreTableStatus.ACTIVE);

        int reservedTableCount = oldStoreTables.size() - unReservedTable.size();
        if(reservedTableCount > newTableCount)  //예약된 테이블 수량 > 신규 수량
            throw new BusinessException(ErrorCode.TABLE_LOCKED);
    }

    public void adjust(Store store, String oldTableName, StoreTableBulkUpdateRequest dto){
        List<StoreTable> oldStoreTables = findTableGroup(store.getId(), oldTableName);
        List<StoreTable> unReservedTable = storeTableRepository.findSafeTables(store.getId(), ReservationStatus.CONFIRMED, oldTableName, StoreTableStatus.ACTIVE);

        if(oldStoreTables.isEmpty()){
            if(dto.count()==0)
                throw new BusinessException(ErrorCode.INVALID_TABLE_COUNT);
            create(store, dto.newTableName(), dto.minCapacity(), dto.maxCapacity(), dto.count());
            return;
        }
        if(dto.count() < oldStoreTables.size()){        //예약된 테이블 수량 < 신규 수량 < 기존 수량
            unReservedTable.stream()
                    .limit(oldStoreTables.size() - dto.count())
                    .forEach(storeTable -> storeTable.updateStatus(StoreTableStatus.DELETED));
        }
        else if(dto.count() > oldStoreTables.size())    //예약된 테이블 수량 < 기존 수량 < 신규 수량
            create(store, dto.newTableName(), dto.minCapacity(), dto.maxCapacity(), dto.count() - oldStoreTables.size());

        oldStoreTables.stream()
                .filter(t -> t.getStatus() == StoreTableStatus.ACTIVE)
                .forEach(storeTable -> storeTable.modify(dto.newTableName(), dto.minCapacity(), dto.maxCapacity())
        );
    }

    public boolean existsByStoreIdAndStatus(Long storeId){
        return storeTableRepository.existsByStoreIdAndStatus(storeId, StoreTableStatus.ACTIVE);
    }

    public void bulkUpdateStatus(List<Long> storeIds){
        storeTableRepository.bulkUpdateStatus(storeIds, StoreTableStatus.DELETED);
    }
}
