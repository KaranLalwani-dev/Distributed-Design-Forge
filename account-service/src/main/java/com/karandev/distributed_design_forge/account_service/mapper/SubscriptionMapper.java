package com.karandev.distributed_design_forge.account_service.mapper;

import com.karandev.distributed_design_forge.account_service.dto.subscription.PlanDto;
import com.karandev.distributed_design_forge.account_service.dto.subscription.SubscriptionResponse;
import com.karandev.distributed_design_forge.account_service.entity.Plan;
import com.karandev.distributed_design_forge.account_service.entity.Subscription;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SubscriptionMapper {

    SubscriptionResponse toSubscriptionResponse(Subscription subscription);

    PlanDto toPlanResponse(Plan plan);
}

