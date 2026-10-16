package com.uav.remote.controller;

import com.uav.remote.entity.Task;
import com.uav.remote.service.TaskService;
import com.uav.remote.repository.InterpretationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InterpretationSelectionTest {
    @Test
    void forwardsSelectedImageIdsAndReturnsBatchCount() throws Exception {
        TaskService service = mock(TaskService.class);
        Task task = new Task(); task.setAlgorithm("camouflaged_object_detection");
        when(service.get(1L)).thenReturn(task);
        when(service.resolveInputs(1L, task.getAlgorithm(), List.of(20L), null)).thenReturn(List.of("second.png"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskController(service)).build();
        mvc.perform(post("/api/tasks/1/interpretations").contentType("application/json")
                .content("{\"image_ids\":[20]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageCount").value(1))
                .andExpect(jsonPath("$.imagePaths[0]").value("second.png"));
        verify(service).interpret(1L, task.getAlgorithm(), List.of("second.png"));
    }

    @Test
    void taskFilterIsAppliedBeforePagination() throws Exception {
        InterpretationRepository repository = mock(InterpretationRepository.class);
        when(repository.searchPaged(isNull(), isNull(), eq(7L), any())).thenReturn(Page.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterpretationController(repository)).build();
        mvc.perform(get("/api/interpretations/page").param("taskId", "7").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
        verify(repository).searchPaged(isNull(), isNull(), eq(7L), argThat(page -> page.getPageNumber() == 1));
    }
}
