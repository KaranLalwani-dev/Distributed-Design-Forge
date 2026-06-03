package com.karandev.distributed_design_forge.workspace_service.controller;

import com.karandev.distributed_design_forge.common_lib.dto.FileTreeDto;
import com.karandev.distributed_design_forge.workspace_service.dto.project.FileContentResponse;
import com.karandev.distributed_design_forge.workspace_service.dto.project.FileTreeResponse;
import com.karandev.distributed_design_forge.workspace_service.service.ProjectFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/files")
public class FileController {

    private final ProjectFileService projectFileService;

    @GetMapping
    public ResponseEntity<FileTreeDto> getFileTree(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectFileService.getFileTree(projectId));
    }

    @GetMapping("/content")
    public ResponseEntity<String> getFile(
            @PathVariable Long projectId,
            @RequestParam String path) {
        return ResponseEntity.ok(projectFileService.getFileContent(projectId, path));
    }

}
