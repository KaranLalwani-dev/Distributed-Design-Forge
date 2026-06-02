package com.karandev.distributed_design_forge.workspace_service.entity;

import com.karandev.distributed_design_forge.common_lib.enums.PreviewStatus;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Preview {

    Long id;

    Project project;

    String namespace;
    String podName;
    String previewUrl;

    PreviewStatus status;

    Instant startedAt;
    Instant terminatedAt;

    Instant createdAt;

}

